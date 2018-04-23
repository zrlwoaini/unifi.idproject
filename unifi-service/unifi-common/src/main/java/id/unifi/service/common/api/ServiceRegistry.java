package id.unifi.service.common.api;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.LOWER_HYPHEN;
import com.google.common.reflect.ClassPath;
import id.unifi.service.common.api.annotations.ApiOperation;
import id.unifi.service.common.api.annotations.ApiService;
import id.unifi.service.common.api.errors.UnknownMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServiceRegistry {
    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);
    private final ComponentHolder componentProvider;

    static class Operation {
        final Class<?> cls;
        final Map<String, Param> params;
        final InvocationType invocationType;
        final String resultMessageType;
        final Type resultType;
        final Method method;

        private Operation(Class<?> cls,
                  Method method,
                  Map<String, Param> params,
                  InvocationType invocationType,
                  Type resultType,
                  @Nullable String resultMessageType) {
            this.cls = cls;
            this.method = method;
            this.params = params;
            this.invocationType = invocationType;
            this.resultType = resultType;
            this.resultMessageType = resultMessageType;
        }
    }

    static class Param {
        final Type type;
        final boolean nullable;

        private Param(Type type, boolean nullable) {
            this.type = type;
            this.nullable = nullable;
        }
    }

    private final Map<Class<?>, Object> serviceInstances;
    private final Map<String, Operation> operations;

    public ServiceRegistry(Map<String, String> packageNamesByModule, ComponentHolder componentHolder) {
        ClassPath classPath;
        try {
            classPath = ClassPath.from(ServiceRegistry.class.getClassLoader());
        } catch (IOException e) {
            throw new RuntimeException("Can't scan classpath, only system and URL-based class loaders supported.", e);
        }

        var services = packageNamesByModule.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> discoverServices(classPath, e.getValue())));

        this.componentProvider = componentHolder;
        this.serviceInstances = createServiceInstances(services.values());
        this.operations = preloadOperations(services);
    }

    public Object invokeRpc(Operation operation, Object[] params) {
        try {
            return operation.method.invoke(serviceInstances.get(operation.cls), params);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw e.getTargetException() instanceof RuntimeException
                    ? (RuntimeException) e.getCause()
                    : new RuntimeException(e);
        }
    }

    public void invokeMulti(Operation operation, Object[] params, MessageListener<?> listenerParam) {
        var allParams = Stream.concat(Arrays.stream(params), Stream.of(listenerParam)).toArray();
        try {
            operation.method.invoke(serviceInstances.get(operation.cls), allParams);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw e.getTargetException() instanceof RuntimeException
                    ? (RuntimeException) e.getCause()
                    : new RuntimeException(e);
        }
    }

    public Operation getOperation(String messageType) {
        var operation = operations.get(messageType);
        if (operation == null) {
            throw new UnknownMessageType(messageType);
        }
        return operation;
    }

    private static Map<Class<?>, ApiService> discoverServices(ClassPath classPath, String packageName) {
        Map<Class<?>, ApiService> classes = new HashMap<>();
        for (var classInfo : classPath.getTopLevelClasses(packageName)) {
            var cls = classInfo.load();
            var annotation = cls.getDeclaredAnnotation(ApiService.class);
            if (annotation != null) {
                classes.put(cls, annotation);
            }
        }
        return classes;
    }

    private Map<Class<?>, Object> createServiceInstances(Collection<Map<Class<?>, ApiService>> services) {
        return services.stream()
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toMap(Function.identity(), cls -> {
                    log.debug("Creating service instance for {}", cls);
                    return componentProvider.get(cls);
                }));
    }

    private static Map<String, Operation> preloadOperations(Map<String, Map<Class<?>, ApiService>> services) {
        Map<String, Operation> operations = new HashMap<>();
        for (var module : services.entrySet()) {
            var moduleName = module.getKey();
            for (var service : module.getValue().entrySet()) {
                var cls = service.getKey();
                var serviceAnnotation = service.getValue();
                var serviceName = serviceAnnotation.value();
                var operationNamespace = moduleName + "." + serviceName;
                for (var method : cls.getDeclaredMethods()) {
                    var operationAnnotation = method.getAnnotation(ApiOperation.class);
                    if (operationAnnotation == null) continue;

                    var operationName = operationAnnotation.name().isEmpty()
                            ? LOWER_CAMEL.to(LOWER_HYPHEN, method.getName())
                            : operationAnnotation.name();
                    var messageType = operationNamespace + "." + operationName;
                    var returnType = method.getGenericReturnType();
                    var methodParams = method.getParameters();

                    var multiReturnType = getMultiResponseReturnType(returnType, methodParams);
                    var invocationType = multiReturnType != null ? InvocationType.MULTI : InvocationType.RPC;
                    Map<String, Param> params;
                    switch (invocationType) {
                        case MULTI:
                            params = preloadParams(Arrays.copyOfRange(methodParams, 0, methodParams.length - 1));
                            operations.put(messageType,
                                    new Operation(cls, method, params, InvocationType.MULTI, multiReturnType, null));
                            break;
                        case RPC:
                            var annotatedResultType = operationAnnotation.resultType();
                            var resultTypeName = annotatedResultType.isEmpty()
                                    ? messageType + "-result"
                                    : annotatedResultType.startsWith(".") ? operationNamespace + annotatedResultType : annotatedResultType;

                            params = preloadParams(methodParams);
                            operations.put(messageType,
                                    new Operation(cls, method, params, InvocationType.RPC, returnType, resultTypeName));
                            break;
                    }
                }
            }
        }
        return operations;
    }

    private static Map<String, Param> preloadParams(Parameter[] methodParameters) {
        Map<String, Param> params = new LinkedHashMap<>(methodParameters.length);
        for (var parameter : methodParameters) {
            if (!parameter.isNamePresent()) {
                throw new RuntimeException(
                        "Method parameter names not found. Java compiler must be called with -parameter.");
            }
            params.put(parameter.getName(), new Param(parameter.getParameterizedType(), parameter.isAnnotationPresent(Nullable.class)));
        }
        return params;
    }

    private static Type getMultiResponseReturnType(Type methodReturnType, Parameter[] methodParameters) {
        if (!methodReturnType.equals(Void.TYPE)) return null;

        var lastParamType = methodParameters[methodParameters.length - 1].getParameterizedType();
        if (!(lastParamType instanceof ParameterizedType)) return null;
        var type = (ParameterizedType) lastParamType;
        if (type.getRawType() != MessageListener.class) return null;
        return type.getActualTypeArguments()[0];
    }
}

import Capsule from "./capsule";
import UnifiWsClient from "./lib/unifi-ws-client";

//Get environment variables and define defaults:
var env = require("env-variable")({
    CAPSULE_BRIDGE_CLIENT_ID: "test-club",
    CAPSULE_BRIDGE_OPERATOR_USERNAME: "test",
    CAPSULE_BRIDGE_OPERATOR_PASSWORD: "test",
    CAPSULE_BRIDGE_WEBSOCKET_URI: "ws://127.0.0.1:8000/service/json",
    CAPSULE_BRIDGE_WEBSOCKET_TYPE: "json"
});

var config = {
    clientId: env.CAPSULE_BRIDGE_CLIENT_ID,
    operatorUsername: env.CAPSULE_BRIDGE_OPERATOR_USERNAME,
    operatorPassword: env.CAPSULE_BRIDGE_OPERATOR_PASSWORD,
    websocketUri: env.CAPSULE_BRIDGE_WEBSOCKET_URI,
    websocketType: env.CAPSULE_BRIDGE_WEBSOCKET_TYPE
};
console.debug(`Configuration: ${JSON.stringify(config)}`);

const CAPSULE_API_KEY = "2CHDNqokGLBzmYXUa6Ce62S2WhT26OhzJnsOl7bnQZ0YWsU5J3GN4yzEHY/h6ywK";

async function fullSync() {
    console.debug("Syncing at " + (new Date()));

    // Get the Capsule data.
    const capsule = new Capsule(CAPSULE_API_KEY);
    const persons = await capsule.getPersons();

    // Connect to the Unifi.id service.
    let unifi = new UnifiWsClient({
        url: config.websocketUri,
        type: config.websocketType
    });
    await unifi.connect();
    unifi.start();

    // Authenticate as a system operator.
    unifi.request({
        "messageType": "core.operator.auth-password",
        "payload": {
            "clientId": config.clientId,
            "username": config.operatorUsername,
            "password": config.operatorPassword
        }
    },
    (authResponse) => {
        console.debug(authResponse);
        persons.forEach((person) => {

            unifi.request({
                "messageType": "core.holder.add-holder",
                "payload": {
                    "clientId": config.clientId,
                    "clientReference": person.id.toString(),
                    "holderType": "contact",
                    "name": `${person.firstName} ${person.lastName}`,
                    "active": true,
                    "image": null
                }
            },
            (response) => {
                console.debug(response);
                if (response.messageType === "core.error.already-exists") {

                    unifi.request({
                        "messageType": "core.holder.edit-holder",
                        "payload": {
                            "clientId": config.clientId,
                            "clientReference": person.id.toString(),
                            "holderType": "contact",
                            "changes": {
                                "name": `${person.firstName} ${person.lastName}`,
                                "active": true,
                                "image": null
                            }
                        }
                    },
                    (response) => {
                        console.debug(response);
                    });

                }
            });

        });
    });

    // Queue up the next sync in 10 minutes.
    setTimeout(fullSync, 600000);
}

fullSync().catch((e) => console.error(e));
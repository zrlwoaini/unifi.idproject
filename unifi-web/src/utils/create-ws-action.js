import pick from 'lodash/pick'
import { WSPackage } from 'lib/ws'

// Function to generate action creator to handle websocket action
export default ({
  type, // Action type
  messageType, // WebSocket message type
  fields, // accepted action parameter field names that will be picked from payload
  defaultParams, // default paramater values in case fields are missing in payload
  selectorKey,
  subscribe = false,
  payloadOnSuccess,
  payloadOnFail
}) => (payload, formSubmit) => {
  // payload: action parameter that are passed to action creator
  const wsType = subscribe ? 'socketSubscribe' : 'socketRequest'

  const pack = new WSPackage({
    protocolVersion: '1.0.0',
    releaseVersion: '1.0.0',
    messageType,
    payload: {
      ...(fields ? pick(defaultParams, fields) : defaultParams),
      ...(fields ? pick(payload, fields) : payload),
    }
  })

  return {
    type,
    [wsType]: pack.content,
    selectorKey: selectorKey || messageType,
    payloadOnSuccess,
    payloadOnFail,
    formSubmit
  }
}

/*
  eslint-disable
  no-param-reassign
 */
import msgpack from 'msgpack-lite'

const binarryCodec = msgpack.createCodec({ binarraybuffer: true, preset: true })

export default class WebSocketLayer {
  constructor(wsConnectionURL) {
    this.wsConnectionURL = wsConnectionURL
    this.socket = null
    this.corelations = {}
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.socket = new WebSocket(this.wsConnectionURL)
      this.socket.binaryType = 'arraybuffer'
      this.once('error', reject)
      this.once('open', resolve)
    })
  }

  close() {
    this.socket.close()
  }

  listen(callback, parse = true, message = 'message') {
    let handler = callback
    if (parse) {
      handler = (event) => {
        console.log('listen', event.data)
        this.decodeJSONFromBlob(event.data)
          .then((content) => {
            callback(event, content)
          })
      }
    }
    this.socket.addEventListener(message, handler)
    return handler
  }

  unlisten(callback, message = 'message') {
    this.socket.removeEventListener(message, callback)
  }

  encodeJSON(json) {
    return msgpack.encode(json, { codec: binarryCodec })
  }

  decodeJSONFromBlob(response) {
    if (typeof Blob !== 'undefined' && response instanceof Blob) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.addEventListener('load', () => {
          resolve(msgpack.decode(new Uint8Array(reader.result, 0, reader.result.byteLength)))
        })
        reader.addEventListener('error', () => {
          reject(reader.error)
        })
        reader.addEventListener('abort', () => {
          reject(reader.error)
        })
        reader.readAsArrayBuffer(response)
      })
    } else if (typeof Buffer !== 'undefined' && response instanceof Buffer) {
      return Promise.resolve(msgpack.decode(new Uint8Array(response)))
    } else if (typeof ArrayBuffer !== 'undefined' && response instanceof ArrayBuffer) {
      return Promise.resolve(msgpack.decode(new Uint8Array(response)))
    }

    return Promise.reject(new Error({ message: 'Unsupported message type' }))
  }

  send(content) {
    if (typeof content !== 'object') {
      throw new Error('Only JSON objects are supported for sending via socket layer')
    }
    const encodedContent = this.encodeJSON(content)
    // console.log(content)
    // console.log('sending encoded', encodedContent)
    // console.log('buffer data view', encodedContent.buffer)
    this.socket.send(encodedContent.buffer)
    // console.log(JSON.stringify(encodedContent))
    // this.socket.send(content)
  }

  sendEncoded(message) {
    this.socket.send(message)
  }

  once(messageType, callback) {
    this.socket.addEventListener(messageType, (event) => {
      callback(event)
      this.socket.removeEventListener(messageType, callback)
    })
  }
}

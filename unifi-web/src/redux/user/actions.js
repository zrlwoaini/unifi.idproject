import { WSPackage } from '../../lib/ws'

import {
  USER_LOGOUT,
  USER_LOGIN,
  USER_REAUTHENTICATE,
  USER_SET_INITIALIZED
} from './types'
import {clientId} from "../../index"

export const loginRequest = ({ username, password, /* remember */ formSubmit }) => {

  const pack = new WSPackage({
    protocolVersion: '1.0.0',
    releaseVersion: '1.0.0',
    messageType: 'core.operator.auth-password',
    payload: { username, password, clientId },
  })

  return {
    socketRequest: pack.content,
    type: USER_LOGIN,
    formSubmit
  }
}

export const reauthenticateRequest = (sessionToken) => {

  const pack = new WSPackage({
    protocolVersion: '1.0.0',
    releaseVersion:  '1.0.0',
    messageType: 'core.operator.auth-token',
    payload: { clientId, sessionToken }
  })

  return {
    socketRequest: pack.content,
    type: USER_REAUTHENTICATE
  }

}

export const logoutRequest = () => ({
  type: USER_LOGOUT
})

export const setInitialized = () => ({
  type: USER_SET_INITIALIZED
})

export default null

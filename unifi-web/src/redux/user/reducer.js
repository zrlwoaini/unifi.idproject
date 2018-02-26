import { REHYDRATE } from 'redux-persist'

import {
  USER_LOGOUT,
  USER_LOGIN,
  USER_REAUTHENTICATE,
  USER_SET_INITIALIZED,
  SET_PASSWORD,
  CHANGE_PASSWORD,
  REQUEST_PASSWORD_RESET,
  CANCEL_PASSWORD_RESET,
  PASSWORD_RESET_INFO_FETCH
} from './types'
import { API_SUCCESS, API_FAIL, API_PENDING } from 'redux/api/request'

const initialState = {
  isLoggingIn: false,
  currentUser: null,
  initialising: true,
  passwordResetInfo: {
    status: 'INIT',
    payload: null
  },
  setPasswordStatus: 'INIT',
  changePasswordStatus: 'INIT',
  requestPasswordResetStatus: 'INIT',
  cancelPasswordResetStatus: 'INIT'
}

const reducer = (state = initialState, action = {}) => {
  switch (action.type) {
    case `${USER_LOGIN}_PENDING`:
      return {
        ...state,
        currentUser: null,
        isLoggingIn: true,
        error: null,
      }

    case `${USER_LOGIN}_FULFILLED`:
      return {
        ...state,
        currentUser: action.payload.payload,
        isLoggingIn: false,
      }

    case `${USER_LOGIN}_REJECTED`:
      return {
        ...state,
        currentUser: null,
        isLoggingIn: false,
        error: action.payload.payload.message,
      }

    case USER_LOGOUT:
      return {
        ...state,
        currentUser: null,
      }

    case REHYDRATE:
      return {
        ...state,
        currentUser: action.payload ? action.payload.user.currentUser : state.currentUser
      }

    case USER_SET_INITIALIZED:
      return {
        ...state,
        initialising: false
      }

    case `${USER_REAUTHENTICATE}_FULFILLED`:
      return {
        ...state,
        ...action.payload.user,
        initialising: false,
      }

    case `${USER_REAUTHENTICATE}_REJECTED`:
      return {
        ...state,
        currentUser: null,
        initialising: false,
      }

    case `${PASSWORD_RESET_INFO_FETCH}_FULFILLED`:
      return {
        ...state,
        passwordResetInfo: {
          ...action.payload.payload,
          status: action.payload.payload ? API_SUCCESS : API_FAIL
        }
      }

    case `${PASSWORD_RESET_INFO_FETCH}_REJECTED`:
      return {
        ...state,
        passwordResetInfo: {
          ...action.payload.payload,
          status: API_FAIL
        }
      }

    case `${SET_PASSWORD}_FULFILLED`:
      return {
        ...state,
        currentUser: action.payload.payload,
        setPasswordStatus: API_SUCCESS
      }

    case `${SET_PASSWORD}_REJECTED`:
      return {
        ...state,
        setPasswordStatus: API_FAIL
      }

    case `${CHANGE_PASSWORD}_FULFILLED`:
      return {
        ...state,
        changePasswordStatus: API_SUCCESS
      }

    case `${CHANGE_PASSWORD}_PENDING`:
      return {
        ...state,
        changePasswordStatus: API_PENDING
      }

    case `${CHANGE_PASSWORD}_REJECTED`:
      return {
        ...state,
        changePasswordStatus: API_FAIL
      }

    case `${REQUEST_PASSWORD_RESET}_FULFILLED`:
      return {
        ...state,
        requestPasswordResetStatus: API_SUCCESS
      }

    case `${REQUEST_PASSWORD_RESET}_REJECTED`:
      return {
        ...state,
        requestPasswordResetStatus: API_FAIL
      }

    case `${CANCEL_PASSWORD_RESET}_FULFILLED`:
      return {
        ...state,
        cancelPasswordResetStatus: API_SUCCESS
      }

    case `${CANCEL_PASSWORD_RESET}_PENDING`:
      return {
        ...state,
        cancelPasswordResetStatus: API_PENDING
      }

    case `${CANCEL_PASSWORD_RESET}_REJECTED`:
      return {
        ...state,
        cancelPasswordResetStatus: API_FAIL
      }

    default:
      return state
  }
}

export default reducer

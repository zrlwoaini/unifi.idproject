import fp from 'lodash/fp'

export const getReducer = state => state.settings

export const siteSelector = fp.compose(
  fp.get('sitesList[0]'),
  getReducer
)

export const siteIdSelector = fp.compose(
  fp.get('siteId'),
  siteSelector
)

export const programmesSelector = fp.compose(
  fp.get('programmesList'),
  getReducer
)

export const holdersSelector = fp.compose(
  fp.get('holdersList'),
  getReducer
)

export const holdersMetaSelector = fp.compose(
  fp.get('holdersMetaList'),
  getReducer
)

import fp from 'lodash/fp'

export const getReducer = fp.get('settings')

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

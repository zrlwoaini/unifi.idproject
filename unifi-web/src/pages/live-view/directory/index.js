import React, { Component } from 'react'
import moment from 'moment'
import fp from 'lodash/fp'

import { compose } from 'redux'
import { connect } from 'react-redux'
import { createStructuredSelector } from 'reselect'
import { withRouter } from 'react-router-dom'

import {
  actions as zonesActions,
  selectors as zoneSelectors,
} from 'redux/zones'

import {
  actions as settingsActions,
  selectors as settingsSelectors
} from 'redux/settings'

import * as ROUTES from 'utils/routes'

import { PageContentTitle, PageContentUnderTitle } from 'components'
import { Collapse } from 'elements'
import { getQueryParams } from 'utils/helpers'
import { groupItems, filterItems } from './utils/helpers'
import { withClientId } from 'hocs'

import FiltersHeader from './components/filters-header'
import GroupingHeader from './components/grouping-header'
import ViewModeHeader from './components/view-mode-header'
import AssetList from './components/asset-list'
import AssetGrid from './components/asset-grid'

import './index.scss'

const getQueryString = (params) => {
  const {
    search, filters, grouping, view,
  } = params
  if (!filters.length && !grouping && !view) {
    return null
  }

  const result = {}
  if (filters.length) {
    result.filter = filters.join(',')
  }

  if (grouping) {
    result.grouping = grouping
  }

  if (view) {
    result.view = view
  }

  if (search) {
    result.search = search
  }

  const keys = Object.keys(result)
  if (keys.length === 0) {
    return ''
  }

  const resultString = `?${keys[0]}=${result[keys[0]]}`

  return keys.slice(1).reduce((acc, key) => {
    acc = `${acc}&${key}=${result[key]}`
    return acc
  }, resultString)
}

const zonesSelector = fp.compose(
  (zonesInfo) => (
    fp.compose(
      fp.sortBy('name'),
      fp.map(key => zonesInfo[key]),
      fp.keys
    )(zonesInfo)
  ),
  zoneSelectors.zonesInfoSelector
)

class DirectoryView extends Component {
  constructor(props) {
    super(props)

    this.state = {
      queryParams: getQueryParams(this.props.location.search),
      itemsPerRow: 4,
    }
  }

  componentDidMount() {
    const { listSites, listZones, listHolder, listenToSubscriptions, clientId } = this.props
    listSites(clientId)
      .then((result) => {
        const { siteId } = this.props
        listZones(clientId, siteId)
        listHolder(clientId, siteId)
        listenToSubscriptions(clientId, siteId)
      })
      .catch(err => console.error(err))
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.location.search !== this.props.location.search) {
      this.setState({
        queryParams: getQueryParams(nextProps.location.search),
      })
    }
  }

  setURLHref(params) {
    const { history } = this.props
    history.push(`${ROUTES.LIVE_VIEW_DIRECTORY}${getQueryString(params)}`)
  }

  handleFilterChange = (value) => {
    const {
      filters, grouping, view, search,
    } = this.state.queryParams
    let newFilters = filters

    const key = Object.keys(value)[0]
    const position = filters.indexOf(key)
    if (value[key] && position === -1) {
      newFilters.push(key)
    }

    if (!value[key] && position !== -1) {
      newFilters = [...filters.slice(0, position), ...filters.slice(position + 1)]
    }

    this.setURLHref({
      filters: newFilters, grouping, view, search,
    })
  }

  handleGroupingChange = (value) => {
    this.setURLHref({
      ...this.state.queryParams, grouping: value,
    })
  }

  handleViewModeChange = (ev) => {
    this.setURLHref({
      ...this.state.queryParams, view: ev.target.value,
    })
  }

  handleSearch = (search) => {
    this.setURLHref({
      ...this.state.queryParams, search: encodeURIComponent(search),
    })
  }

  renderContentList(items) {
    if (this.state.queryParams.view === 'list') {
      return <AssetList items={items} />
    }

    return <AssetGrid items={items} itemsPerRow={this.state.itemsPerRow} />
  }

  renderZoneGroupTitle(groupName, items) {
    return (
      <div>{groupName} - {items.length} contacts</div>
    )
  }

  renderContent(filteredItems) {
    const { zoneId } = this.state
    const { grouping } = this.state.queryParams
    const { zones } = this.props

    if (grouping === 'zones') {
      const zoneItems = fp.compose(
        fp.reverse,
        fp.sortBy('detectionTime'),
        fp.filter(item => item.zone.zoneId === zoneId)
      )(filteredItems)

      return this.renderContentList(zoneItems)
    } else {
      const sortedItems = fp.compose(
        fp.reverse,
        fp.sortBy('detectionTime')
      )(filteredItems)
      return this.renderContentList(sortedItems)
    }
  }

  handleItemsPerRowChange = (value) => {
    this.setState({
      itemsPerRow: value
    })
  }

  handleZoneChange = (value) => {
    this.setState({
      zoneId: value
    })
  }

  render() {
    const {
      filters, grouping, view, search,
    } = this.state.queryParams

    const {
      liveDiscoveryUpdate,
      discoveredList,
      zones
    } = this.props

    const filteredItems = filterItems(discoveredList, filters, search)
    return (
      <div className="directory-view-container">

        <PageContentTitle>Directory View</PageContentTitle>
        <PageContentUnderTitle>
          Last update {moment(liveDiscoveryUpdate).fromNow()}
        </PageContentUnderTitle>

        <FiltersHeader
          onSearch={this.handleSearch}
          onChange={this.handleFilterChange}
          searchValue={search ? decodeURIComponent(search) : ''}
          filterValues={filters}
        />

        <GroupingHeader
          onChange={this.handleGroupingChange}
          groupValue={grouping}
        />

        <ViewModeHeader
          onViewModeChange={this.handleViewModeChange}
          viewMode={view}
          grouping={grouping}
          resultCount={filteredItems.length}
          itemsPerRow={this.state.itemsPerRow}
          onItemsPerRowChange={this.handleItemsPerRowChange}
          onZoneChange={this.handleZoneChange}
          zoneId={this.state.zoneId}
          zones={zones}
        />

        {this.renderContent(filteredItems)}
      </div>
    )
  }
}

export const selector = createStructuredSelector({
  discoveredList: zoneSelectors.getDiscoveredList,
  siteId: settingsSelectors.siteIdSelector,
  liveDiscoveryUpdate: compose(
    fp.get('liveDiscoveryUpdate'),
    zoneSelectors.getReducer
  ),
  zones: zonesSelector
})

export const actions = {
  ...zonesActions,
  listSites: settingsActions.listSites
}

export default compose(
  withRouter,
  withClientId,
  connect(selector, actions),
)(DirectoryView)

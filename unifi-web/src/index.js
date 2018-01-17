import React from 'react'
import ReactDOM from 'react-dom'

import './styles/less/index.less'
import './styles/index.scss'

import Main from './main'
import registerServiceWorker from './registerServiceWorker'

export const clientId = window.location.hostname.split(".")[0];

ReactDOM.render(<Main />, document.getElementById('root'));
registerServiceWorker();

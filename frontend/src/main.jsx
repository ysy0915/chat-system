import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles/base.css'
import './styles/navbar.css'
import './styles/chat.css'
import './styles/admin.css'
import './styles/landing.css'
import './styles/sql.css'
import './styles/auth.css'
import './styles/graph.css'
import './styles/media.css'
import './styles/mobile.css'
import './styles/profile.css'
import './styles/about.css'
import './styles/debate.css'
import './styles/monitor.css'
import './styles/responsive.css'
import './styles/game.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)

{
  "name": "FullStamina",
  "gameId": "net.stardewvalley",
  "description": "Setzt Leben und Ausdauer auf Maximum per Overlay-Button",
  "triggerMode": "ON_DEMAND",
  "patches": [
    { "field": "health",  "operation": "SET", "amount": 100 },
    { "field": "stamina", "operation": "SET", "amount": 270 }
  ],
  "overlayActions": [
    { "label": "Full Health",   "patchFields": ["health"]  },
    { "label": "Full Stamina",  "patchFields": ["stamina"] }
  ]
}

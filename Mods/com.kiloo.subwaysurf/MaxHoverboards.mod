{
  "name": "MaxHoverboards",
  "gameId": "com.kiloo.subwaysurf",
  "description": "Setzt Hoverboards auf 5 per Overlay-Button",
  "triggerMode": "ON_DEMAND",
  "patches": [
    { "field": "hoverboards", "operation": "SET", "amount": 5 }
  ],
  "overlayActions": [
    { "label": "5 Hoverboards", "patchFields": ["hoverboards"] }
  ]
}

{
  "name": "DiamondKit",
  "gameId": "com.mojang.minecraftpe",
  "description": "Setzt Diamanten und Smaragde auf 64 per Overlay-Button",
  "triggerMode": "ON_DEMAND",
  "patches": [
    { "field": "diamonds", "operation": "SET", "amount": 64 },
    { "field": "emeralds", "operation": "SET", "amount": 64 }
  ],
  "overlayActions": [
    { "label": "64 Diamonds", "patchFields": ["diamonds"] },
    { "label": "64 Emeralds", "patchFields": ["emeralds"] }
  ]
}

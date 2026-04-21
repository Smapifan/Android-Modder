{
  "name": "MaxGems",
  "gameId": "com.gram.mergedragons",
  "description": "Setzt Edelsteine auf 999 per Overlay-Button",
  "triggerMode": "ON_DEMAND",
  "patches": [
    { "field": "GemCount",     "operation": "SET", "amount": 999 },
    { "field": "ChaliceCount", "operation": "SET", "amount": 5   }
  ],
  "overlayActions": [
    { "label": "999 Gems",    "patchFields": ["GemCount"]     },
    { "label": "5 Chalices",  "patchFields": ["ChaliceCount"] }
  ]
}

{
  "name": "MaxCoins",
  "gameId": "com.kiloo.subwaysurf",
  "description": "Fügt 10.000 Coins und 10 Schlüssel hinzu",
  "triggerMode": "ON_LAUNCH",
  "patches": [
    { "field": "coins", "operation": "ADD", "amount": 10000 },
    { "field": "keys",  "operation": "ADD", "amount": 10    }
  ]
}

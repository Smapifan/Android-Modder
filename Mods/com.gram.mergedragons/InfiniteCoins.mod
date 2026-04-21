{
  "name": "InfiniteCoins",
  "gameId": "com.gram.mergedragons",
  "description": "Fügt 10.000 Coins zum Spielstand hinzu",
  "triggerMode": "ON_LAUNCH",
  "patches": [
    { "field": "CoinCount", "operation": "ADD", "amount": 10000 }
  ]
}

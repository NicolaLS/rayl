// Runs tools/blink-stack/bin/fixture through the stack's helper (host, port 4476). ACTION:
//   user      FUNDED_SATS [SCOPES]       -> output.blinkApiKey, a new Blink user's API key
//   invoice   SATS                        -> output.invoice = {bolt11, paymentHash} (outside node)
//   received  PAYMENT_HASH EXPECTED_SATS  -> fails unless the outside node got exactly that much
//   balance   API_KEY EXPECTED_SATS       -> fails unless the user's BTC balance is that much;
//             FEE_TEXT (optional, e.g. "Fee: 2 sat" from the screen) is subtracted first
var base = typeof RAYL_BLINK_HELPER_URL !== 'undefined' ? RAYL_BLINK_HELPER_URL : 'http://127.0.0.1:4476'

function fixture(args) {
  var response = http.post(base + '/fixture', {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ args: args })
  })
  if (!response.ok) throw new Error('Blink stack fixture ' + args[0] + ': ' + response.body + ' (is tools/blink-stack running?)')
  return json(response.body)
}

if (ACTION === 'user') {
  var args = ['user', FUNDED_SATS]
  if (typeof SCOPES !== 'undefined') args.push(SCOPES)
  output.blinkApiKey = fixture(args).apiKey
} else if (ACTION === 'invoice') {
  var invoice = fixture(['invoice', SATS])
  output.invoice = { bolt11: invoice.paymentRequest, paymentHash: invoice.paymentHash }
} else if (ACTION === 'received') {
  var received = fixture(['received', PAYMENT_HASH]).paidSats
  if (received !== Number(EXPECTED_SATS)) {
    throw new Error('The outside node got ' + received + ' sat, expected ' + EXPECTED_SATS)
  }
} else if (ACTION === 'balance') {
  var balance = fixture(['balance', API_KEY]).btcSats
  var fee = 0
  if (typeof FEE_TEXT !== 'undefined') {
    var match = /([0-9][0-9,.]*)/.exec(FEE_TEXT)
    if (!match) throw new Error('No fee in "' + FEE_TEXT + '"')
    fee = Number(match[1].replace(/[,.]/g, ''))
  }
  if (balance !== Number(EXPECTED_SATS) - fee) {
    throw new Error("The Blink user's balance is " + balance + ' sat, expected ' + EXPECTED_SATS + ' - fee ' + fee)
  }
} else {
  throw new Error('Unknown ACTION ' + ACTION)
}

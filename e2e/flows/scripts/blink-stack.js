// Runs tools/blink-stack/bin/fixture through the stack's helper (host, port 4476). ACTION:
//   user      FUNDED_SATS [SCOPES]       -> output.blinkApiKey, a new Blink user's API key
//   invoice   SATS                        -> output.invoice = {bolt11, paymentHash} (outside node)
//   received  PAYMENT_HASH EXPECTED_SATS  -> fails unless the outside node got exactly that much
//   balance   API_KEY EXPECTED_SATS       -> fails unless the user's BTC balance is that much;
//             FEE_TEXT (optional, e.g. "Fee: 2 sat" from the screen) is subtracted first
//   balance-paid-once API_KEY BEFORE_SATS AMOUNT_SATS -> fails unless the balance dropped by
//             the amount plus at most 10 sat of fees (once, not twice)
//   hold-invoice SATS                     -> output.invoice = {bolt11, paymentHash, preimage}
//   await-held PAYMENT_HASH               -> waits until the payment reached the outside node
//   settle PREIMAGE                       -> the outside node takes the held payment
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
} else if (ACTION === 'balance-paid-once') {
  var after = fixture(['balance', API_KEY]).btcSats
  var spent = Number(BEFORE_SATS) - after
  if (spent < Number(AMOUNT_SATS) || spent > Number(AMOUNT_SATS) + 10) {
    throw new Error('Blink spent ' + spent + ' sat for a ' + AMOUNT_SATS + ' sat payment')
  }
} else if (ACTION === 'hold-invoice') {
  var held = fixture(['hold-invoice', SATS])
  output.invoice = { bolt11: held.paymentRequest, paymentHash: held.paymentHash, preimage: held.preimage }
} else if (ACTION === 'await-held') {
  var state = ''
  for (var i = 0; i < 120 && state !== 'ACCEPTED'; i++) {
    state = fixture(['received', PAYMENT_HASH]).state
  }
  if (state !== 'ACCEPTED') throw new Error("Blink's payment never reached the outside node: " + state)
} else if (ACTION === 'settle') {
  fixture(['settle', PREIMAGE])
} else {
  throw new Error('Unknown ACTION ' + ACTION)
}

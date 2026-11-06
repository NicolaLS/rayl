// Talks to tools/nwc-stack's helper (runs on the host, like Maestro). ACTION selects what to do:
//   connect-uri                      -> output.nwcUri, an NWC URI of the payer node whose relay
//                                       the device can reach (emulator: 10.0.2.2)
//   invoice   AMOUNT_MSAT (or "any") -> output.invoice = {bolt11, paymentHash}
//   received  PAYMENT_HASH EXPECTED_MSAT -> fails unless the receiver got exactly that much
var base = typeof TEST_WALLET_SERVICE_URL !== 'undefined' ? TEST_WALLET_SERVICE_URL : 'http://127.0.0.1:8082'
var relayPort = typeof RAYL_NWC_RELAY_PORT !== 'undefined' ? RAYL_NWC_RELAY_PORT : '8081'
var deviceHost = maestro.platform === 'android' ? '10.0.2.2' : '127.0.0.1'

function post(path, body) {
  var response = http.post(base + path, {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!response.ok) throw new Error(path + ' failed: ' + response.status + ' ' + response.body)
  return json(response.body)
}

if (ACTION === 'connect-uri') {
  var response = http.get(base + '/get-nwc-uri?node=payer&label=maestro-' + Date.now())
  if (!response.ok) throw new Error('Is tools/nwc-stack running? ' + response.status)
  output.nwcUri = json(response.body).uri.replace(
    /relay=[^&]*/,
    'relay=' + encodeURIComponent('ws://' + deviceHost + ':' + relayPort)
  )
} else if (ACTION === 'invoice') {
  var body = { label: 'maestro-' + Date.now(), description: 'Rayl Maestro flow' }
  if (AMOUNT_MSAT !== 'any') body.amount_msat = Number(AMOUNT_MSAT)
  var invoice = post('/create-invoice', body)
  output.invoice = { bolt11: invoice.bolt11, paymentHash: invoice.paymentHash }
} else if (ACTION === 'received') {
  var status = post('/invoice-status', { paymentHash: PAYMENT_HASH })
  var received = status.settled ? Number(status.amountReceivedMsat) : 0
  if (received !== Number(EXPECTED_MSAT)) {
    throw new Error('The receiver got ' + received + ' msat, expected ' + EXPECTED_MSAT)
  }
} else {
  throw new Error('Unknown ACTION ' + ACTION)
}

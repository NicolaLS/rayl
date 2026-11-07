#!/usr/bin/env python3
import json
import os
import subprocess
import time
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, parse_qsl, urlencode, urlsplit, urlunsplit


HOST = os.environ.get("RAYL_NWC_HELPER_HOST", "0.0.0.0")
PORT = int(os.environ.get("RAYL_NWC_HELPER_CONTAINER_PORT", "8080"))
PUBLIC_RELAY_URL = os.environ.get("RAYL_NWC_PUBLIC_RELAY_URL", "ws://127.0.0.1:8081")
NODES = {
    "payer": "/cln/payer",
    "receiver": "/cln/receiver",
}
# Alby Hub, the second NWC wallet service that can pay (node "albyhub").
ALBYHUB_URL = os.environ.get("RAYL_NWC_ALBYHUB_URL", "http://albyhub:8080")
ALBYHUB_PASSWORD = os.environ.get("RAYL_NWC_ALBYHUB_PASSWORD", "rayl-regtest")
ALBYHUB_SCOPES = ["pay_invoice", "lookup_invoice", "get_info", "get_balance", "list_transactions"]
ALBYHUB_LOCK = threading.Lock()
# Alby Hub keeps one relay subscription per connection, and nostr-rs-relay allows 32 per client
# connection (fixed in 0.9.0). Every test creates a connection, so older test connections are
# deleted to stay well below that. Only connections this helper created are touched.
ALBYHUB_APP_PREFIX = "rayl-test:"
ALBYHUB_KEEP_APPS = 20


class RequestError(Exception):
    def __init__(self, status, message, detail=None):
        super().__init__(message)
        self.status = status
        self.message = message
        self.detail = detail


def cln(node, args, timeout=30):
    if node not in NODES:
        raise RequestError(400, f"Unknown node '{node}'. Expected one of: {', '.join(NODES)}")
    cmd = [
        "lightning-cli",
        "--network=regtest",
        f"--lightning-dir={NODES[node]}",
        *args,
    ]
    try:
        proc = subprocess.run(
            cmd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RequestError(504, f"lightning-cli timed out: {' '.join(args)}") from exc

    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise RequestError(502, f"lightning-cli failed: {' '.join(args)}", detail)
    return proc.stdout.strip()


def cln_json(node, args, timeout=30):
    raw = cln(node, args, timeout)
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RequestError(502, f"lightning-cli returned non-JSON output for {' '.join(args)}", raw) from exc


def first(value, fallback=""):
    if isinstance(value, list):
        return value[0] if value else fallback
    return value if value is not None else fallback


def find_nip47_uri(value, label):
    if isinstance(value, list):
        for item in value:
            uri = find_nip47_uri(item, label)
            if uri:
                return uri
    if isinstance(value, dict):
        if label in value:
            uri = find_nip47_uri(value[label], label)
            if uri:
                return uri
        uri = value.get("uri")
        entry_label = value.get("label")
        if isinstance(uri, str) and uri and (not entry_label or entry_label == label):
            return uri
        for nested in value.values():
            uri = find_nip47_uri(nested, label)
            if uri:
                return uri
    return ""


def rewrite_nwc_relay(uri, relay):
    parsed = urlsplit(uri)
    query = parse_qsl(parsed.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key != "relay"]
    query.append(("relay", relay))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), parsed.fragment))


def create_or_get_nwc_uri(node, label, budget_msat, budget_interval):
    try:
        listed = cln_json(node, ["nip47-list", label])
        uri = find_nip47_uri(listed, label)
        if uri:
            return rewrite_nwc_relay(uri, PUBLIC_RELAY_URL)
    except RequestError:
        pass

    created = cln_json(node, ["nip47-create", label, budget_msat, budget_interval])
    uri = find_nip47_uri(created, label)
    if not uri:
        raise RequestError(502, f"nip47-create returned no URI for label '{label}'", created)
    return rewrite_nwc_relay(uri, PUBLIC_RELAY_URL)


def albyhub(method, path, body=None, token=None, timeout=30):
    """One Alby Hub API call. Unlocking and creating apps are rate limited to about one per
    second for all callers, so a 429 is retried."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    for _ in range(20):
        request = urllib.request.Request(f"{ALBYHUB_URL}{path}", data=data, method=method)
        request.add_header("content-type", "application/json")
        if token:
            request.add_header("authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            if exc.code == 429:
                time.sleep(1.1)
                continue
            raise RequestError(502, f"Alby Hub {method} {path} answered {exc.code}", detail) from exc
        except urllib.error.URLError as exc:
            raise RequestError(502, f"Alby Hub is not reachable: {exc.reason}") from exc
    raise RequestError(502, f"Alby Hub {method} {path} stayed rate limited")


def create_albyhub_nwc_uri(label, budget_msat):
    with ALBYHUB_LOCK:
        token = albyhub(
            "POST", "/api/unlock", {"unlockPassword": ALBYHUB_PASSWORD, "permission": "full"}
        )["token"]
        prune_albyhub_apps(token)
        created = albyhub("POST", "/api/apps", {
            "name": f"{ALBYHUB_APP_PREFIX}{label}",
            "maxAmountSat": int(budget_msat) // 1000,
            "budgetRenewal": "never",
            "scopes": ALBYHUB_SCOPES,
        }, token=token)
    uri = created.get("pairingUri", "")
    if not uri:
        raise RequestError(502, "Alby Hub returned no pairing URI", created)
    return rewrite_nwc_relay(uri, PUBLIC_RELAY_URL)


def prune_albyhub_apps(token):
    listed = albyhub("GET", "/api/apps?limit=500", token=token).get("apps", [])
    ours = sorted(
        (app for app in listed if app.get("name", "").startswith(ALBYHUB_APP_PREFIX)),
        key=lambda app: app.get("id", 0),
    )
    for app in ours[: max(0, len(ours) - (ALBYHUB_KEEP_APPS - 1))]:
        albyhub("DELETE", f"/api/apps/{app['appPubkey']}", token=token)


def invoice_status(node, payment_hash="", label=""):
    invoices = cln_json(node, ["listinvoices"]).get("invoices", [])
    for invoice in invoices:
        if payment_hash and invoice.get("payment_hash") == payment_hash:
            return invoice_status_result(invoice)
        if label and invoice.get("label") == label:
            return invoice_status_result(invoice)
    return {
        "found": False,
        "settled": False,
        "paymentHash": payment_hash,
        "label": label,
    }


def invoice_status_result(invoice):
    status = invoice.get("status", "")
    result = {
        "found": True,
        "settled": status == "paid",
        "status": status,
        "paymentHash": invoice.get("payment_hash", ""),
        "label": invoice.get("label", ""),
        "bolt11": invoice.get("bolt11", ""),
        "invoice": invoice,
    }
    for source, target in [
        ("amount_msat", "amountMsat"),
        ("amount_received_msat", "amountReceivedMsat"),
        ("paid_at", "paidAt"),
        ("payment_preimage", "preimage"),
    ]:
        if invoice.get(source) is not None:
            result[target] = invoice[source]
    return result


def wait_invoice_paid(node, payment_hash, label, timeout_ms, interval_ms):
    deadline = time.time() + (timeout_ms / 1000)
    while time.time() <= deadline:
        status = invoice_status(node, payment_hash, label)
        if status.get("settled") is True or status.get("status") == "expired":
            return status
        time.sleep(interval_ms / 1000)
    return invoice_status(node, payment_hash, label)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def log_message(self, fmt, *args):
        print("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), fmt % args), flush=True)

    def dispatch(self):
        try:
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query)
            if self.command == "GET" and parsed.path == "/health":
                self.handle_health()
            elif self.command == "GET" and parsed.path == "/get-nwc-uri":
                self.handle_get_nwc_uri(query)
            elif self.command == "POST" and parsed.path == "/create-invoice":
                self.handle_create_invoice()
            elif self.command == "POST" and parsed.path == "/invoice-status":
                self.handle_invoice_status()
            elif self.command == "POST" and parsed.path == "/wait-invoice-paid":
                self.handle_wait_invoice_paid()
            elif self.command == "POST" and parsed.path == "/pay-invoice":
                self.handle_pay_invoice()
            else:
                raise RequestError(404, f"No route for {self.command} {parsed.path}")
        except RequestError as exc:
            body = {"error": exc.message}
            if exc.detail:
                body["detail"] = exc.detail
            self.write_json(exc.status, body)
        except Exception as exc:
            self.write_json(500, {"error": str(exc)})

    def read_json(self):
        length = int(self.headers.get("content-length", "0"))
        if length == 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise RequestError(400, "Request body must be JSON", raw) from exc

    def write_json(self, status, body):
        raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def handle_health(self):
        nodes = {}
        for node in NODES:
            info = cln_json(node, ["getinfo"], timeout=5)
            nodes[node] = {
                "id": info.get("id", ""),
                "alias": info.get("alias", node),
            }
        hub = albyhub("GET", "/api/info", timeout=5)
        if not hub.get("running"):
            raise RequestError(503, "Alby Hub is not running", hub.get("startupError"))
        nodes["albyhub"] = {"version": hub.get("version", "")}
        self.write_json(200, {"ok": True, "relay": PUBLIC_RELAY_URL, "nodes": nodes})

    def handle_get_nwc_uri(self, query):
        node = first(query.get("node"), "payer")
        label = first(query.get("label"), f"rayl-nwc-{node}")
        budget_msat = first(query.get("budget_msat"), "100000000")
        budget_interval = first(query.get("budget_interval"), "1d")
        if node == "albyhub":
            uri = create_albyhub_nwc_uri(label, budget_msat)
        else:
            uri = create_or_get_nwc_uri(node, label, budget_msat, budget_interval)
        self.write_json(200, {"uri": uri, "node": node, "label": label, "relay": PUBLIC_RELAY_URL})

    def handle_create_invoice(self):
        body = self.read_json()
        node = body.get("node") or "receiver"
        amount = body.get("amount_msat") or body.get("amountMsat") or "any"
        label = body.get("label") or f"rayl-nwc-{int(time.time() * 1000)}"
        description = body.get("description") or "Rayl test invoice"
        args = ["invoice", str(amount), str(label), str(description)]
        if body.get("expiry"):
            args.append(str(int(body["expiry"])))
        invoice = cln_json(node, args)
        self.write_json(200, {
            "bolt11": invoice.get("bolt11", ""),
            "paymentHash": invoice.get("payment_hash", ""),
            "label": label,
            "amountMsat": amount,
            "node": node,
        })

    def handle_invoice_status(self):
        body = self.read_json()
        node = body.get("node") or "receiver"
        status = invoice_status(node, body.get("paymentHash", ""), body.get("label", ""))
        self.write_json(200, status)

    def handle_wait_invoice_paid(self):
        body = self.read_json()
        node = body.get("node") or "receiver"
        payment_hash = body.get("paymentHash", "")
        label = body.get("label", "")
        if not payment_hash and not label:
            raise RequestError(400, "paymentHash or label is required")
        timeout_ms = int(body.get("timeoutMs") or 120000)
        interval_ms = int(body.get("intervalMs") or 2000)
        status = wait_invoice_paid(node, payment_hash, label, timeout_ms, interval_ms)
        self.write_json(200, status)

    def handle_pay_invoice(self):
        body = self.read_json()
        node = body.get("node") or "payer"
        bolt11 = body.get("bolt11", "")
        if not bolt11:
            raise RequestError(400, "bolt11 is required")
        result = cln_json(node, ["pay", bolt11], timeout=120)
        self.write_json(200, {"node": node, "result": result})


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Rayl NWC helper listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

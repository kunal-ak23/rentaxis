"""Shared HTTP client for the scale scripts (header auth, like web/walkthrough/ui-sweep.spec.ts)."""
import json
import threading
import time

import requests

_local = threading.local()


class Api:
    def __init__(self, base, user_id=None, role=None, tenant_id=None):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json"}
        if user_id:
            self.headers["X-User-Id"] = str(user_id)
            self.headers["X-User-Role"] = role
        if tenant_id:
            self.headers["X-Tenant-Id"] = str(tenant_id)
            self.headers["X-User-Tenant-Id"] = str(tenant_id)

    def _session(self):
        s = getattr(_local, "s", None)
        if s is None:
            s = requests.Session()
            a = requests.adapters.HTTPAdapter(pool_connections=4, pool_maxsize=4)
            s.mount("http://", a)
            _local.s = s
        return s

    def call(self, method, path, json_body=None, params=None, files=None, data=None, timeout=600, ok=(200, 201, 204)):
        headers = dict(self.headers)
        if files is not None:
            headers.pop("Content-Type", None)
        r = self._session().request(method, self.base + path, headers=headers, params=params,
                                    data=data if files is not None else (None if json_body is None else json.dumps(json_body)),
                                    files=files, timeout=timeout)
        if r.status_code not in ok:
            raise ApiError(f"{method} {path} -> {r.status_code}: {r.text[:400]}", r.status_code, r.text)
        if not r.content:
            return None
        try:
            return r.json()
        except ValueError:
            return r.text

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, json_body=body, **kw)

    def put(self, path, body=None, **kw):
        return self.call("PUT", path, json_body=body, **kw)


class ApiError(RuntimeError):
    def __init__(self, msg, status, text):
        super().__init__(msg)
        self.status = status
        self.text = text


def items(payload):
    if isinstance(payload, dict):
        for k in ("content", "items", "data"):
            if isinstance(payload.get(k), list):
                return payload[k]
        return []
    return payload or []


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)

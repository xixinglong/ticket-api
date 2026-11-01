"""Read-only business smoke check; credentials never leave private local files."""
import http.cookiejar
import json
from pathlib import Path
import urllib.parse
import urllib.request

settings = Path.home() / '.config/ticket-api'
accounts = json.loads((settings / 'accounts.json').read_text())
admin = json.loads((settings / 'admin.json').read_text())
accounts[admin['username']] = admin['password']
for name, password in accounts.items():
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def get(path):
        with client.open('http://127.0.0.1:8080' + path, timeout=15) as response:
            return json.load(response)
    token = get('/api/csrf')
    request = urllib.request.Request('http://127.0.0.1:8080/api/login',
        data=urllib.parse.urlencode({'username': name, 'password': password}).encode(),
        headers={token['headerName']: token['token']})
    with client.open(request, timeout=15) as response:
        assert response.status == 204
    assert get('/api/me')['username'] == name
    page = get('/api/admin/users' if name == admin['username'] else '/api/tickets')
    assert 'items' in page
    print(name + ': login, identity and authorized list passed')
with urllib.request.urlopen('http://127.0.0.1:8080', timeout=15) as response:
    assert b'Ticket Lab' in response.read()
print('Business UI and five account smoke checks passed; no business writes performed.')

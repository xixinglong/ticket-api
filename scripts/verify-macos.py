"""Local acceptance runner; never uses ticket_lab for test writes."""
import configparser
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HOME = Path.home()
OUT = ROOT / 'target/macos-acceptance'
MYSQL = HOME / '.local/opt/mysql-8.4.13-macos15-arm64/bin/mysql'
JAVA_HOME = HOME / 'Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home'
CNF = HOME / '.config/mysql84/root.cnf'
DB = 'ticket_verify_' + time.strftime('%Y%m%d_%H%M%S')
OUT.mkdir(parents=True, exist_ok=True)
config = configparser.ConfigParser(interpolation=None)
config.read(CNF)
env = os.environ.copy()
env.update(JAVA_HOME=str(JAVA_HOME), DB_URL=f'jdbc:mysql://127.0.0.1:3306/{DB}?sslMode=REQUIRED',
           DB_USER=config['client']['user'], DB_PASSWORD=config['client']['password'])
passwords = {name: secrets.token_hex(16) for name in ('alice', 'bob', 'agent1', 'agent2')}
env.update({'LAB_' + name.upper() + '_PASSWORD': password for name, password in passwords.items()})


def sql(text):
    return subprocess.check_output([str(MYSQL), '--defaults-extra-file=' + str(CNF), '-N', '-B'],
                                   input=text, text=True)


class Client:
    def __init__(self, name=None):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if name:
            self.request('/api/login', 204, urllib.parse.urlencode(
                {'username': name, 'password': passwords[name]}).encode(), form=True)

    def request(self, path, expected=200, data=None, form=False, csrf=True, method=None):
        headers = {}
        if data is not None and csrf:
            token = self.request('/api/csrf')
            headers[token['headerName']] = token['token']
        if data is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            if not isinstance(data, bytes):
                data = json.dumps(data).encode()
        request = urllib.request.Request('http://127.0.0.1:18080' + path, data=data, headers=headers, method=method)
        try:
            response = self.client.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            assert response.status == expected, (path, response.status, expected, body)
            result = json.loads(body) if body else None
            if expected >= 400:
                assert result['code'] and result['message'] and result['requestId']
                assert result['requestId'] == response.headers['X-Request-Id']
            return result


def start(label, extra=None):
    local_env = env.copy()
    if extra:
        local_env.update(extra)
        local_env = {key: value for key, value in local_env.items() if value is not None}
    log = (OUT / (label + '.log')).open('w')
    process = subprocess.Popen([str(JAVA_HOME / 'bin/java'), '-jar',
        str(ROOT / 'target/ticket-api-0.0.1-SNAPSHOT.jar'), '--server.port=18080',
        '--spring.datasource.hikari.connection-timeout=1500'], env=local_env, cwd=ROOT, stdout=log, stderr=log)
    log.close()
    return process


def ready(process):
    for _ in range(60):
        assert process.poll() is None, 'Server exited before readiness'
        try:
            Client().request('/api/csrf')
            return
        except (urllib.error.URLError, TimeoutError):
            time.sleep(1)
    raise AssertionError('Server readiness timeout')


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


with socket.socket() as probe:
    probe.bind(('127.0.0.1', 18080))
sql(f'CREATE DATABASE `{DB}` CHARACTER SET utf8mb4; USE `{DB}`;\n' + (ROOT / 'sql/01-schema.sql').read_text())
print('Isolated database:', DB, flush=True)
subprocess.run(['sh', './mvnw', '-B', 'dependency:build-classpath', '-Dmdep.outputFile=target/migration-classpath.txt'],
               cwd=ROOT, env=env, stdout=subprocess.DEVNULL, check=True)
classpath = (ROOT / 'target/migration-classpath.txt').read_text().strip()
migrate = [str(JAVA_HOME / 'bin/java'), '--class-path', classpath, 'scripts/MigrateUsers.java']
test_env = env.copy()
test_env.update({'LAB_' + name.upper() + '_PASSWORD': 'test-' + name + '-password' for name in passwords})
subprocess.run(migrate, cwd=ROOT, env=test_env, check=True)
subprocess.run(migrate, cwd=ROOT, env=test_env, check=True)
sql(f'USE `{DB}`;\n' + (ROOT / 'sql/04-user-management.sql').read_text())
admin_env = env.copy()
admin_env.update(ADMIN_USERNAME='admin', ADMIN_PASSWORD='test-admin-password')
bootstrap = [str(JAVA_HOME / 'bin/java'), '--class-path', classpath, 'scripts/BootstrapAdmin.java']
subprocess.run(bootstrap, cwd=ROOT, env=admin_env, check=True)
subprocess.run(bootstrap, cwd=ROOT, env=admin_env, check=True)
with (OUT / 'maven.log').open('w') as log:
    subprocess.run(['sh', './mvnw', '-B', 'package'], cwd=ROOT, env=env,
                   stdout=log, stderr=subprocess.STDOUT, check=True)
reports = [ET.parse(path).getroot() for path in (ROOT / 'target/surefire-reports').glob('TEST-*.xml')]
assert reports, 'No test reports'
totals = {key: sum(int(report.get(key, '0')) for report in reports)
          for key in ('tests', 'failures', 'errors', 'skipped')}
assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, totals
print('Tests:', totals, flush=True)
passwords = {name: 'test-' + name + '-password' for name in passwords}
process = start('http')
try:
    ready(process)
    Client().request('/api/tickets', 401)
    alice, bob, agent, other = [Client(name) for name in passwords]
    first = alice.request('/api/tickets', 201, {'title': 'Acceptance cancel', 'description': 'Restart evidence'})['id']
    second = alice.request('/api/tickets', 201, {'title': 'Acceptance resolve'})['id']
    bob.request('/api/tickets/' + first, 404)
    alice.request('/api/tickets/' + first + '/claim', 403, {})
    alice.request('/api/tickets/' + first + '/cancel', 403, {}, csrf=False)
    alice.request('/api/tickets/' + first + '/cancel', 204, {})
    alice.request('/api/tickets/' + first + '/cancel', 409, {})
    agent.request('/api/tickets/' + second + '/claim', 204, {})
    other.request('/api/tickets/' + second, 404)
    agent.request('/api/tickets/' + second + '/resolve', 204, {})
    agent.request('/api/tickets/' + second + '/resolve', 409, {})
    alice.request('/api/tickets?page=0', 400)
    page = alice.request('/api/tickets?page=1&size=1')
    assert page['total'] >= 2
    before = alice.request('/api/tickets/' + second)
    assert before['status'] == 'RESOLVED'
    events = sql(f"USE `{DB}`; SELECT ticket_id,action FROM ticket_events WHERE ticket_id IN ({first},{second}) ORDER BY id;")
    assert events.count('CANCEL') == events.count('CLAIM') == events.count('RESOLVE') == 1
    passwords['admin'] = 'test-admin-password'
    administrator = Client('admin')
    administrator.request('/api/tickets', 403)
    alice.request('/api/admin/users', 403)
    created = administrator.request('/api/admin/users', 201, {
        'username': 'managed-http', 'password': 'managed-http-password', 'role': 'AGENT'})
    assert set(created) == {'id', 'username', 'role', 'enabled'}
    passwords['managed-http'] = 'managed-http-password'
    managed = Client('managed-http')
    base = '/api/admin/users/' + created['id']
    administrator.request(base + '/enabled', 204, {'enabled': False}, method='PATCH')
    administrator.request(base + '/enabled', 204, {'enabled': True}, method='PATCH')
    managed.request('/api/me', 401)
    managed = Client('managed-http')
    administrator.request(base + '/reset-password', 204, {'password': 'replacement-http-password'})
    managed.request('/api/me', 401)
    Client().request('/api/login', 401, urllib.parse.urlencode({
        'username': 'managed-http', 'password': 'managed-http-password'}).encode(), form=True)
    passwords['managed-http'] = 'replacement-http-password'
    Client('managed-http').request('/api/me')
    page = administrator.request('/api/admin/users?size=100')
    assert any(item['id'] == created['id'] for item in page['items'])
    assert all(set(item) == {'id', 'username', 'role', 'enabled'} for item in page['items'])
finally:
    stop(process)
process = start('restart')
try:
    ready(process)
    alice.request('/api/me', 401)
    assert Client('alice').request('/api/tickets/' + second) == before
    Client('managed-http').request('/api/me')
    Client('admin').request('/api/admin/users')
finally:
    stop(process)
process = start('absent-config', {'DB_URL': None})
try:
    assert process.wait(timeout=40) != 0
finally:
    stop(process)
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 0))
    unavailable_port = probe.getsockname()[1]
    process = start('database-unavailable', {'DB_URL': f'jdbc:mysql://127.0.0.1:{unavailable_port}/{DB}?connectTimeout=1000'})
    try:
        ready(process)
        error = Client().request('/api/login', 401, urllib.parse.urlencode(
            {'username': 'alice', 'password': passwords['alice']}).encode(), form=True)
        assert not any(word in json.dumps(error).lower() for word in ('jdbc', 'sql', 'password', 'exception'))
    finally:
        stop(process)
sql(f"USE `{DB}`; INSERT INTO users(id,username,password_hash,role,enabled) WITH RECURSIVE n AS (SELECT 1000 id UNION ALL SELECT id+1 FROM n WHERE id<1099) SELECT id,CONCAT('fixture-',id),'disabled','USER',FALSE FROM n WHERE id NOT IN (1001,1002);")
sql(f"USE `{DB}`; INSERT INTO tickets(title,creator_id,status) SELECT 'Explain fixture', 1000 + MOD(a.n + 10*b.n + 100*c.n + 1000*d.n,100), 'OPEN' FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d;")
explain = sql(f'USE `{DB}`; ANALYZE TABLE tickets; EXPLAIN SELECT * FROM tickets WHERE creator_id=1001 ORDER BY id DESC LIMIT 20 OFFSET 0; EXPLAIN SELECT * FROM tickets WHERE (status=\'OPEN\' AND assignee_id IS NULL) OR assignee_id=2001 ORDER BY id DESC LIMIT 20 OFFSET 0;')
result = {'database': DB, 'tests': totals, 'http': 'passed', 'restart': 'passed',
          'user_management_http': 'create, pagination, role isolation, disable/enable, reset and old-session rejection passed',
          'absent_database_configuration': 'startup rejected',
          'database_unavailable': 'Login rejected with safe HTTP 401',
          'events': events, 'explain': explain,
          'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
          'jar_sha256': hashlib.sha256((ROOT / 'target/ticket-api-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest()}
(OUT / 'result.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2), flush=True)

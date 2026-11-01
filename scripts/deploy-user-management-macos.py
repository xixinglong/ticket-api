"""One-time local upgrade. Caller must stop the business application first."""
import configparser
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
HOME = Path.home()
os.umask(0o077)
BIN = HOME / '.local/opt/mysql-8.4.13-macos15-arm64/bin'
CNF = HOME / '.config/mysql84/root.cnf'
JAVA = HOME / 'Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home/bin/java'
with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1', 8080)) == 0:
        raise RuntimeError('Stop business application on 8080 before migration')

def sql(text):
    return subprocess.check_output([str(BIN / 'mysql'), '--defaults-extra-file=' + str(CNF),
                                    '-N', '-B', 'ticket_lab'], input=text, text=True).strip()

if sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='ticket_lab' AND table_name='users' AND column_name='auth_version'") != '0':
    raise RuntimeError('Migration already started or completed; inspect schema, do not rerun')
if sql("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='ticket_lab' AND referenced_table_name='users'") != '3':
    raise RuntimeError('Unexpected user foreign keys')
snapshot_query = "SELECT id,username,password_hash,role,enabled FROM users ORDER BY id; SELECT * FROM tickets ORDER BY id; SELECT * FROM ticket_events ORDER BY id;"
before = sql(snapshot_query)
backup = HOME / '.local/share/ticket-api/backups' / ('ticket_lab_before_management_' + time.strftime('%Y%m%d_%H%M%S') + '.sql')
backup.parent.mkdir(parents=True, exist_ok=True)
with backup.open('x') as output:
    subprocess.run([str(BIN / 'mysqldump'), '--defaults-extra-file=' + str(CNF),
                    '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', 'ticket_lab'], stdout=output, check=True)
sql((ROOT / 'sql/04-user-management.sql').read_text())
if sql(snapshot_query) != before:
    raise RuntimeError('Existing data changed; keep application stopped and inspect')
assert sql("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='ticket_lab' AND referenced_table_name='users'") == '3'
settings = HOME / '.config/ticket-api/admin.json'
if settings.exists():
    admin = json.loads(settings.read_text())
else:
    admin = {'username': 'admin', 'password': secrets.token_urlsafe(24)}
    with settings.open('x') as output:
        json.dump(admin, output, indent=2)
settings.chmod(0o600)
config = configparser.ConfigParser(interpolation=None)
config.read(CNF)
env = os.environ.copy()
env.update(DB_URL='jdbc:mysql://127.0.0.1:3306/ticket_lab?sslMode=REQUIRED',
           DB_USER=config['client']['user'], DB_PASSWORD=config['client']['password'],
           ADMIN_USERNAME=admin['username'], ADMIN_PASSWORD=admin['password'])
subprocess.run([str(JAVA), '--class-path', (ROOT / 'target/migration-classpath.txt').read_text().strip(),
                'scripts/BootstrapAdmin.java'], cwd=ROOT, env=env, check=True)
print('Upgrade complete; original users, passwords, tickets and events unchanged.')
print('Backup:', backup)
print('Private admin credentials:', settings)

"""Start the UI against a previously verified isolated database, never ticket_lab."""
import configparser
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
home = Path.home()
result = json.loads((root / 'target/macos-acceptance/result.json').read_text())
database = result['database']
if not database.startswith('ticket_verify_') or not database.replace('_', '').isalnum():
    raise ValueError('Preview requires an isolated verification database')
config = configparser.ConfigParser(interpolation=None)
config.read(home / '.config/mysql84/ticket_app.cnf')
env = os.environ.copy()
env.update(DB_URL=f'jdbc:mysql://127.0.0.1:3306/{database}?sslMode=REQUIRED',
           DB_USER=config['client']['user'], DB_PASSWORD=config['client']['password'])
java = home / 'Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home/bin/java'
log_path = home / '.local/share/ticket-api/frontend-preview.log'
with log_path.open('a') as log:
    process = subprocess.Popen([str(java), '-jar', str(root / 'target/ticket-api-0.0.1-SNAPSHOT.jar'),
                               '--server.port=18081'], cwd=root, env=env,
                              stdout=log, stderr=log, start_new_session=True)
print(f'Preview PID {process.pid}; http://127.0.0.1:18081; isolated database {database}')

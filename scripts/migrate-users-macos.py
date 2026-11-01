"""Back up and migrate local ticket_lab; stop application writers before running."""
import configparser
import json
import os
from pathlib import Path
import subprocess
import time

root = Path(__file__).resolve().parents[1]
home = Path.home()
os.umask(0o077)
config = configparser.ConfigParser(interpolation=None)
config.read(home / '.config/mysql84/root.cnf')
accounts = json.loads((home / '.config/ticket-api/accounts.json').read_text())
java_home = home / 'Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home'
env = os.environ.copy()
env.update(JAVA_HOME=str(java_home), DB_URL='jdbc:mysql://127.0.0.1:3306/ticket_lab?sslMode=REQUIRED',
           DB_USER=config['client']['user'], DB_PASSWORD=config['client']['password'])
env.update({'LAB_' + name.upper() + '_PASSWORD': password for name, password in accounts.items()})
backup = home / '.local/share/ticket-api/backups'
backup.mkdir(mode=0o700, parents=True, exist_ok=True)
destination = backup / ('ticket_lab_' + time.strftime('%Y%m%d_%H%M%S') + '.sql')
with destination.open('x') as output:
    subprocess.run([str(home / '.local/opt/mysql-8.4.13-macos15-arm64/bin/mysqldump'),
                    '--defaults-extra-file=' + str(home / '.config/mysql84/root.cnf'),
                    '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', 'ticket_lab'],
                   stdout=output, check=True)
subprocess.run(['sh', './mvnw', '-B', 'dependency:build-classpath', '-Dmdep.outputFile=target/migration-classpath.txt'],
               cwd=root, env=env, stdout=subprocess.DEVNULL, check=True)
classpath = (root / 'target/migration-classpath.txt').read_text().strip()
subprocess.run([str(java_home / 'bin/java'), '--class-path', classpath, 'scripts/MigrateUsers.java'],
               cwd=root, env=env, check=True)
print('Backup:', destination)

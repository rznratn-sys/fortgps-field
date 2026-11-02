#!/usr/bin/env python3
"""OpenPGP AES-256 backup/restore. No agent, global GPG config, network or key generation."""
import argparse
import getpass
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from owner_key import external


def transform(source, output, password, *, decrypt=False):
    source=Path(source);output=Path(output)
    if not password or '\n' in password or '\r' in password:
        raise ValueError('Invalid backup passphrase')
    if source.is_symlink() or not source.is_file():
        raise ValueError('Expected a regular source file')
    fd=os.open(output,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    try:
        with os.fdopen(fd,'wb') as target, tempfile.TemporaryDirectory(prefix='fortgps-gpg-') as temp:
            os.chmod(temp,0o700)
            # Explicit S2K parameters avoid GPG's agent query for a default S2K count.
            args=['gpg','--no-options','--homedir',temp,'--no-autostart','--batch',
                  '--pinentry-mode','loopback','--no-symkey-cache','--passphrase-fd','0','--status-fd','2']
            if decrypt:
                args+=['--decrypt',str(source)]
            else:
                args+=['--symmetric','--cipher-algo','AES256','--s2k-mode','3',
                       '--s2k-digest-algo','SHA512','--s2k-count','65011712','--compress-algo','none',
                       '--output','-',str(source)]
            result=subprocess.run(args,input=(password+'\n').encode(),stdout=target,stderr=subprocess.PIPE)
            if result.returncode or (decrypt and (b'[GNUPG:] GOODMDC' not in result.stderr or b'[GNUPG:] DECRYPTION_OKAY' not in result.stderr)):
                raise RuntimeError('GPG failed or integrity check missing; partial output removed')
            target.flush();os.fsync(target.fileno())
        if output.stat().st_size==0:raise RuntimeError('Empty backup/restore')
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation',choices=['encrypt','restore'])
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if not sys.stdin.isatty():raise ValueError('Use an interactive private terminal')
    source=external(a.source);output=external(a.output)
    password=getpass.getpass('Outer backup passphrase (NOT the PKCS12 password): ')
    if a.operation=='encrypt':
        if len(password)<20 or getpass.getpass('Repeat outer backup passphrase: ')!=password:
            raise ValueError('Backup passphrases differ or are shorter than 20 characters')
    transform(source,output,password,decrypt=a.operation=='restore')
    print('Encrypted backup written and flushed.' if a.operation=='encrypt' else 'Integrity-checked private restore written. Verify its private-key entry next.')

if __name__=='__main__':
    try:main()
    except (ValueError,RuntimeError,OSError) as e:sys.exit(str(e))

#!/usr/bin/env python3
"""Assina Sai, Capeta! com uma chave permanente criptografada no repositorio.

A chave mestre fica SOMENTE no secret SAICAPETA_MASTER_KEY do GitHub Actions.
Nunca grava chave em claro ou senhas no repositorio.
"""
from pathlib import Path
import base64, hashlib, json, os, secrets, subprocess, sys, tempfile
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PROJECT=Path(sys.argv[1]).resolve()
KEY_FILE=Path("signing/keystore.encrypted.json")
MASTER=os.environ.get("SAICAPETA_MASTER_KEY","")
AAD=b"SaiCapeta stable signing v1"

def b64(b):
    return base64.b64encode(b).decode("ascii")

def decode(s):
    return base64.b64decode(s,validate=True)

def derive(salt):
    return hashlib.pbkdf2_hmac("sha256",MASTER.encode("utf-8"),salt,500000,dklen=32)

def make_keypair():
    password=secrets.token_urlsafe(32)
    alias="saicapeta-release"
    with tempfile.TemporaryDirectory() as tmp:
        p=Path(tmp)/"release.p12"
        subprocess.run([
            "keytool","-genkeypair","-noprompt",
            "-storetype","PKCS12","-keystore",str(p),
            "-alias",alias,"-keyalg","RSA","-keysize","3072",
            "-validity","10950","-storepass",password,
            "-keypass",password,
            "-dname","CN=Sai Capeta, OU=Android App, O=Sai Capeta, C=BR",
        ],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        return {"keystore_b64":b64(p.read_bytes()),"store_password":password,
                "key_password":password,"alias":alias}

def decrypt_key():
    payload=json.loads(KEY_FILE.read_text(encoding="utf-8"))
    if payload.get("format")!="saicapeta-release-key-v1":
        raise RuntimeError("Formato de assinatura desconhecido; nao altere o arquivo.")
    plaintext=AESGCM(derive(decode(payload["salt"]))).decrypt(
        decode(payload["nonce"]),decode(payload["ciphertext"]),AAD)
    return json.loads(plaintext)

def store_encrypted_and_push(data):
    salt=secrets.token_bytes(16)
    nonce=secrets.token_bytes(12)
    ciphertext=AESGCM(derive(salt)).encrypt(
        nonce,json.dumps(data,separators=(",",":")).encode("utf-8"),AAD)
    payload={"format":"saicapeta-release-key-v1","iterations":500000,
             "salt":b64(salt),"nonce":b64(nonce),"ciphertext":b64(ciphertext)}
    KEY_FILE.parent.mkdir(exist_ok=True,parents=True)
    KEY_FILE.write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    # CRITICO: se nao persistir, nao e seguro publicar o primeiro APK.
    subprocess.run(["git","config","user.name","github-actions[bot]"],check=True)
    subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],check=True)
    subprocess.run(["git","add",str(KEY_FILE)],check=True)
    subprocess.run(["git","commit","-m","Guardar chave de assinatura criptografada"],check=True)
    subprocess.run(["git","push","origin","HEAD:main"],check=True)
    print("Chave permanente criptografada armazenada no repositorio. NENHUMA senha publicada.")

def main():
    if len(MASTER)<32:
        raise RuntimeError("Configure o segredo SAICAPETA_MASTER_KEY com pelo menos 32 caracteres.")
    if KEY_FILE.exists():
        try:
            data=decrypt_key()
        except Exception as ex:
            raise RuntimeError("Nao foi possivel abrir a chave permanente: confira o segredo, nao gere outra chave.") from None
        print("Utilizando a chave permanente existente.")
    else:
        print("Primeira publicacao: gerando chave permanente.")
        data=make_keypair()
        store_encrypted_and_push(data)

    release=json.loads(Path("release/version.json").read_text(encoding="utf-8"))
    version=release["versionName"]
    code=int(release["versionCode"])
    tag="v"+version
    # Arquivo temporario é apagado ao final da execucao pelo runner.
    with tempfile.TemporaryDirectory() as temp:
        p=Path(temp)/"release.p12"
        p.write_bytes(decode(data["keystore_b64"]))
        p.chmod(0o600)
        env=os.environ.copy()
        env.update({
            "SAICAPETA_KEYSTORE_PATH":str(p),
            "SAICAPETA_KEYSTORE_PASSWORD":data["store_password"],
            "SAICAPETA_KEY_PASSWORD":data["key_password"],
            "SAICAPETA_KEY_ALIAS":data["alias"],
        })
        subprocess.run(["gradle","-p",str(PROJECT),":app:assembleRelease","--no-daemon"],check=True,env=env)
        apk=PROJECT/"app/build/outputs/apk/release/app-release.apk"
        if not apk.is_file() or apk.stat().st_size<50000:
            raise RuntimeError("APK release nao gerado.")
        sdk=Path(os.environ.get("ANDROID_HOME","/usr/local/lib/android/sdk"))
        verifier=sdk/"build-tools/35.0.0/apksigner"
        subprocess.run([str(verifier),"verify","--verbose",str(apk)],check=True,
                       stdout=subprocess.DEVNULL)
        result=Path(f"Sai-Capeta-{tag}.apk")
        result.write_bytes(apk.read_bytes())
        manifest={
            "versionCode":code,"versionName":version,
            "apkUrl":f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/{tag}/{result.name}",
            "sha256":hashlib.sha256(result.read_bytes()).hexdigest(),
            "notes":release.get("notes","Atualizacao do Sai, Capeta!"),
        }
        Path("atualizacao.json").write_text(
            json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("APK release assinado e verificado; manifesto SHA-256 gerado.")
        print("Arquivo:",result.name,"Codigo:",code)

if __name__=="__main__":
    main()

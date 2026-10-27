package br.com.corvo.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

public final class CorvoUpdater {
    private static final String FEED="https://raw.githubusercontent.com/mais1fraseofc-dot/Sai-Capeta/corvo-navegador-updates/corvo-publish/latest.json";
    private static final long CHECK_INTERVAL=24L*60*60*1000;
    private final Activity host;
    private final SharedPreferences prefs;
    private final DownloadManager manager;
    private BroadcastReceiver receiver;

    public CorvoUpdater(Activity activity,SharedPreferences prefs){
        this.host=activity;this.prefs=prefs;
        manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
        receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context ctx,Intent intent){
                if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))return;
                long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
                if(id<0||id!=CorvoUpdater.this.prefs.getLong("update_download_id",-2))return;
                new Thread(()->verifyAndInstall(id)).start();
            }
        };
        IntentFilter filter=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if(Build.VERSION.SDK_INT>=33) host.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else host.registerReceiver(receiver,filter);
    }
    public void release(){
        if(receiver!=null){try{host.unregisterReceiver(receiver);}catch(Exception ignored){} receiver=null;}
    }
    public void maybeCheck(){
        long now=System.currentTimeMillis();
        if(now-prefs.getLong("update_checked",0)>CHECK_INTERVAL)check(false);
    }
    public void check(boolean interactive){
        new Thread(()->{
            try{
                JSONObject info=new JSONObject(readText(FEED,65536));
                int latest=info.getInt("versionCode");
                String label=info.optString("versionName","nova versão");
                String url=info.getString("apkUrl");
                String sha=info.getString("sha256").toLowerCase(Locale.ROOT);
                if(!url.startsWith("https://raw.githubusercontent.com/mais1fraseofc-dot/Sai-Capeta/")||
                    !url.endsWith(".apk")||!sha.matches("[0-9a-f]{64}"))
                    throw new SecurityException("O endereço de atualização não é confiável");
                PackageInfo installed=host.getPackageManager().getPackageInfo(host.getPackageName(),0);
                long current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
                prefs.edit().putLong("update_checked",System.currentTimeMillis()).apply();
                host.runOnUiThread(()->{
                    if(latest>current){
                        new AlertDialog.Builder(host).setTitle("Atualização do Corvo")
                            .setMessage("Versão "+label+" disponível. O navegador será atualizado sem desinstalar ou apagar os dados. O Android solicitará confirmação.")
                            .setPositiveButton("Atualizar",(d,w)->download(url,sha,label))
                            .setNegativeButton("Depois",null).show();
                    } else if(interactive)toast("O Corvo já está atualizado.");
                });
            }catch(Exception e){
                if(interactive)host.runOnUiThread(()->new AlertDialog.Builder(host)
                    .setTitle("Não foi possível verificar")
                    .setMessage("Confira sua conexão e tente novamente. "+e.getMessage())
                    .setPositiveButton("OK",null).show());
            }
        }).start();
    }
    private void download(String url,String sha,String version){
        if(Build.VERSION.SDK_INT>=26&&!host.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(host).setTitle("Permitir atualização")
                .setMessage("O Android precisa autorizar a instalação de atualizações baixadas pelo Corvo. Esta permissão é solicitada apenas uma vez.")
                .setPositiveButton("Permitir",(d,w)->{
                    try{
                        Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+host.getPackageName()));
                        host.startActivity(i);
                    }catch(Exception e){toast("Abra as permissões do Corvo nas Configurações");}
                }).setNegativeButton("Cancelar",null).show();
            return;
        }
        try{
            String file="Corvo-Navegador-"+version.replaceAll("[^0-9A-Za-z_.-]","_")+".apk";
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Atualização Corvo "+version);
            request.setDescription("Atualização segura do navegador");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,file);
            long id=manager.enqueue(request);
            prefs.edit().putLong("update_download_id",id).putString("update_sha",sha).apply();
            toast("Atualização sendo baixada");
        }catch(Exception e){toast("Não foi possível baixar: "+e.getMessage());}
    }
    private void verifyAndInstall(long id){
        try{
            DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
            int status;
            try(android.database.Cursor cur=manager.query(q)){
                if(!cur.moveToFirst())throw new Exception("Download não encontrado");
                status=cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
            if(status!=DownloadManager.STATUS_SUCCESSFUL)throw new Exception("Falha no download");
            Uri uri=manager.getUriForDownloadedFile(id);
            if(uri==null)throw new Exception("Arquivo não encontrado");
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(InputStream input=host.getContentResolver().openInputStream(uri)){
                byte[] buf=new byte[16384];int n;
                if(input==null)throw new Exception("Arquivo inacessível");
                while((n=input.read(buf))!=-1)md.update(buf,0,n);
            }
            StringBuilder hex=new StringBuilder();
            for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!hex.toString().equalsIgnoreCase(prefs.getString("update_sha","")))
                throw new SecurityException("A assinatura do arquivo recebido não corresponde à versão publicada");
            host.runOnUiThread(()->{
                try{
                    prefs.edit().remove("update_download_id").remove("update_sha").apply();
                    Intent install=new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(uri,"application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    host.startActivity(install);
                }catch(Exception e){toast("Abra o APK na pasta Downloads para concluir a atualização");}
            });
        }catch(Exception e){
            host.runOnUiThread(()->new AlertDialog.Builder(host)
                .setTitle("Atualização interrompida").setMessage(e.getMessage())
                .setPositiveButton("OK",null).show());
        }
    }
    private static String readText(String url,int max) throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(8000);conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent","CorvoBrowser-Updater/1.0");
        try{
            if(conn.getResponseCode()!=200)throw new Exception("Servidor indisponível ("+conn.getResponseCode()+")");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] data=new byte[4096];int n;
                while((n=in.read(data))!=-1){if(out.size()+n>max)throw new Exception("Resposta muito grande");out.write(data,0,n);}
            }
            return out.toString("UTF-8");
        }finally{conn.disconnect();}
    }
    private void toast(String msg){host.runOnUiThread(()->Toast.makeText(host,msg,Toast.LENGTH_LONG).show());}
}

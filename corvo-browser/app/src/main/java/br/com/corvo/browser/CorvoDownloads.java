package br.com.corvo.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.Toast;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CorvoDownloads {
    private static final String LIST_KEY="corvo_downloads";
    private final Activity activity;
    private final DownloadManager dm;
    private final SharedPreferences prefs;

    public CorvoDownloads(Activity activity,SharedPreferences prefs){
        this.activity=activity;this.prefs=prefs;
        this.dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
    }
    public void enqueue(String url,String disposition,String mime,String agent,String referer){
        try{
            Uri address=Uri.parse(url);
            if(address.getScheme()==null ||
               !(address.getScheme().equalsIgnoreCase("https")||address.getScheme().equalsIgnoreCase("http"))){
                notifyUser("Link não pode ser baixado diretamente.");return;
            }
            String filename=URLUtil.guessFileName(url,disposition,mime);
            if(filename==null||filename.isEmpty())filename="corvo-arquivo";
            // Prevent ERROR_FILE_ALREADY_EXISTS on repeated downloads.
            int lastDot=filename.lastIndexOf('.');
            String base=lastDot>0?filename.substring(0,lastDot):filename;
            String extension=lastDot>0?filename.substring(lastDot):"";
            filename=base+"_"+System.currentTimeMillis()+extension;
            // DownloadManager preserves the original bytes. It does NOT convert
            // HTML pages, manifests or adaptive streams into playable movies.
            DownloadManager.Request request=new DownloadManager.Request(address);
            request.setTitle(filename);
            request.setDescription("Corvo Navegador");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,filename);
            if(mime!=null&&!mime.isEmpty())request.setMimeType(mime);
            String cookies=CookieManager.getInstance().getCookie(url);
            if(cookies!=null&&!cookies.isEmpty())request.addRequestHeader("Cookie",cookies);
            if(agent!=null&&!agent.isEmpty())request.addRequestHeader("User-Agent",agent);
            if(referer!=null&&(referer.startsWith("https://")||referer.startsWith("http://")))
                request.addRequestHeader("Referer",referer);
            long id=dm.enqueue(request);
            save(id);
            notifyUser("Adicionado aos downloads. Consulte ⋮ → Meus downloads.");
        }catch(Exception e){
            new AlertDialog.Builder(activity).setTitle("Erro ao baixar")
                .setMessage(e.getMessage()).setPositiveButton("OK",null).show();
        }
    }
    private void notifyUser(String message){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private void save(long id){
        String stored=prefs.getString(LIST_KEY,"");
        String combined=id+(stored.isEmpty()?"":","+stored);
        String[] all=combined.split(",");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(all.length,60);i++){
            if(i>0)out.append(',');
            out.append(all[i]);
        }
        prefs.edit().putString(LIST_KEY,out.toString()).apply();
    }
    private long[] ids(){
        String saved=prefs.getString(LIST_KEY,"");
        if(saved.isEmpty())return new long[0];
        String[] parts=saved.split(",");
        List<Long> values=new ArrayList<>();
        for(String p:parts)try{long value=Long.parseLong(p);if(value>0)values.add(value);}
            catch(NumberFormatException ignored){}
        long[] a=new long[values.size()];
        for(int i=0;i<a.length;i++)a[i]=values.get(i);
        return a;
    }
    private static final class Item{
        long id,size,current;
        int status,reason;
        String name,type,url;
    }
    private List<Item> queryItems(){
        List<Item> entries=new ArrayList<>();
        long[] ids=ids();
        if(ids.length==0)return entries;
        try(Cursor c=dm.query(new DownloadManager.Query().setFilterById(ids))){
            if(c==null)return entries;
            while(c.moveToNext()){
                Item item=new Item();
                item.id=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                item.name=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                item.type=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                item.url=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                item.status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                item.reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                item.size=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                item.current=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                entries.add(item);
            }
        }catch(Exception e){notifyUser("Não foi possível consultar os downloads.");}
        // DownloadManager does not necessarily preserve the requested order.
        List<Item> ordered=new ArrayList<>();
        for(long id:ids)for(Item item:entries)if(item.id==id)ordered.add(item);
        return ordered;
    }
    private String status(Item i){
        switch(i.status){
            case DownloadManager.STATUS_SUCCESSFUL:
                if(i.type!=null&&i.type.toLowerCase(Locale.ROOT).contains("text/html"))
                    return "Baixou uma página HTML, não um vídeo (site pode exigir login)";
                return "Concluído";
            case DownloadManager.STATUS_PENDING: return "Na fila";
            case DownloadManager.STATUS_PAUSED: return "Pausado (motivo "+i.reason+")";
            case DownloadManager.STATUS_FAILED: return "Falhou (código "+i.reason+")";
            case DownloadManager.STATUS_RUNNING:
                if(i.size>0)return "Baixando "+Math.min(100,100*i.current/i.size)+"%";
                return "Baixando";
            default: return "Status desconhecido";
        }
    }
    public void show(){
        List<Item> items=queryItems();
        if(items.isEmpty()){
            new AlertDialog.Builder(activity).setTitle("Meus downloads")
                .setMessage("Nenhum arquivo direto baixado pelo Corvo ainda. Vídeos reproduzidos por transmissão segmentada ou protegida não aparecem aqui como MP4.")
                .setPositiveButton("Downloads do Android",(d,w)->openAndroidDownloads())
                .setNegativeButton("Fechar",null).show();
            return;
        }
        String[] labels=new String[items.size()];
        for(int n=0;n<items.size();n++){
            Item i=items.get(n);
            labels[n]=(i.name==null?"Arquivo":i.name)+"\n"+status(i);
        }
        new AlertDialog.Builder(activity).setTitle("Meus downloads")
            .setItems(labels,(d,n)->showItem(items.get(n)))
            .setPositiveButton("Atualizar lista",(d,w)->show())
            .setNeutralButton("Downloads do Android",(d,w)->openAndroidDownloads())
            .setNegativeButton("Fechar",null).show();
    }
    private void showItem(Item i){
        StringBuilder b=new StringBuilder(status(i));
        if(i.size>0){
            b.append("\nTamanho: ").append(String.format(Locale.ROOT,"%.2f MB",i.size/1048576.0));
            if(i.size<4096 && likelyVideo(i))b.append("\nAVISO: muito pequeno para um vídeo típico. Pode ser uma página, playlist ou download incompleto.");
        }
        if(i.status==DownloadManager.STATUS_SUCCESSFUL){
            String fileType=inspectFile(i);
            if(!fileType.isEmpty())b.append("\nVerificação: ").append(fileType);
        }
        if(i.type!=null)b.append("\nFormato informado: ").append(i.type);
        if(i.url!=null)b.append("\nOrigem: ").append(i.url.length()>160?i.url.substring(0,160)+"…":i.url);
        AlertDialog.Builder d=new AlertDialog.Builder(activity).setTitle(i.name).setMessage(b.toString());
        if(i.status==DownloadManager.STATUS_SUCCESSFUL)
            d.setPositiveButton("Abrir arquivo",(dlg,w)->openItem(i));
        else d.setPositiveButton("Atualizar estado",(dlg,w)->show());
        d.setNeutralButton("Downloads do Android",(dlg,w)->openAndroidDownloads())
            .setNegativeButton("Fechar",null).show();
    }
    private boolean likelyVideo(Item i){
        String name=i.name==null?"":i.name.toLowerCase(Locale.ROOT);
        String type=i.type==null?"":i.type.toLowerCase(Locale.ROOT);
        return type.startsWith("video/") ||
               name.matches("(?s).*\.(mp4|webm|mov|mkv|avi|m4v)$");
    }
    private String inspectFile(Item item){
        if(item.status!=DownloadManager.STATUS_SUCCESSFUL)return "";
        try{
            Uri uri=dm.getUriForDownloadedFile(item.id);
            if(uri==null)return "Arquivo não encontrado";
            try(InputStream in=activity.getContentResolver().openInputStream(uri)){
                if(in==null)return "Arquivo inacessível";
                byte[] data=new byte[128];
                int length=in.read(data);
                if(length<=0)return "Arquivo vazio";
                String text=new String(data,0,length,StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                if(text.startsWith("<!doctype html")||text.startsWith("<html")||
                    text.startsWith("<?xml")||text.contains("<html"))
                    return "HTML/XML recebido em vez do vídeo solicitado";
                if(text.startsWith("#extm3u"))return "Playlist HLS (m3u8), não é vídeo completo";
                if(length>=8&&data[4]=='f'&&data[5]=='t'&&data[6]=='y'&&data[7]=='p')
                    return "Contêiner MP4 reconhecido";
                if(length>=4&&(data[0]&0xff)==0x1a&&(data[1]&0xff)==0x45&&
                   (data[2]&0xff)==0xdf&&(data[3]&0xff)==0xa3)
                    return "Contêiner WebM/Matroska reconhecido";
                if(length>=3&&(data[0]&0xff)==0x49&&(data[1]&0xff)==0x44&&(data[2]&0xff)==0x33)
                    return "Arquivo de áudio MP3 reconhecido";
                if(likelyVideo(item))return "Cabeçalho de vídeo não reconhecido; arquivo pode estar incompleto ou usar outro formato";
                return "Arquivo disponível; formato não identificado automaticamente";
            }
        }catch(Exception e){return "Não foi possível verificar os primeiros bytes";}
    }
    private void openItem(Item i){
        try{
            Uri content=dm.getUriForDownloadedFile(i.id);
            if(content==null){notifyUser("Arquivo não encontrado. Consulte Downloads.");return;}
            String inspection=inspectFile(i);
            if(inspection.startsWith("HTML/XML")||inspection.startsWith("Playlist HLS")||
                inspection.startsWith("Arquivo vazio")){
                new AlertDialog.Builder(activity).setTitle("Arquivo não é um vídeo reproduzível")
                    .setMessage(inspection+"\n\nO servidor não forneceu um vídeo completo. Tente o download oficial do site ou um link direto de arquivo MP4/WebM.")
                    .setPositiveButton("OK",null).show();
                return;
            }
            String type=i.type;
            if(type==null||type.isEmpty()||type.equals("application/octet-stream")){
                String ext=MimeTypeMap.getFileExtensionFromUrl(i.name==null?"":i.name);
                type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            }
            if(type==null)type="*/*";
            Intent open=new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(content,type);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(open,"Abrir arquivo com"));
        }catch(Exception e){
            new AlertDialog.Builder(activity).setTitle("Não foi possível abrir")
                .setMessage("O formato pode não ser compatível ou o arquivo pode ter sido removido. Verifique no aplicativo Downloads do Android.")
                .setPositiveButton("Abrir Downloads",(d,w)->openAndroidDownloads())
                .setNegativeButton("Fechar",null).show();
        }
    }
    public void openAndroidDownloads(){
        try{activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}
        catch(Exception e){notifyUser("Não foi possível abrir a pasta Downloads.");}
    }
}

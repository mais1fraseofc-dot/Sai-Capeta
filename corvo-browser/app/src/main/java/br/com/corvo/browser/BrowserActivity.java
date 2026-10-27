package br.com.corvo.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.webkit.WebResourceError;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.os.Message;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.HashSet;

public abstract class BrowserActivity extends Activity {
    abstract boolean privateMode();
    static final int BG=Color.rgb(8,13,24), BAR=Color.rgb(17,29,47), BLUE=Color.rgb(82,180,255), WHITE=Color.rgb(232,239,247);
    private final ArrayList<WebView> tabs=new ArrayList<>();
    private final LinkedHashSet<String> media=new LinkedHashSet<>();
    private final Map<WebView,String> pageDomains=Collections.synchronizedMap(new WeakHashMap<>());
    private boolean errorDialogVisible=false;
    private final Set<String> wwwRetries=new HashSet<>();
    private LinearLayout layout;
    private FrameLayout page;
    private EditText address;
    private TextView counter;
    private WebView current;
    private boolean unlocked=false,authRunning=false,blocked=true;
    private View lockedScreen,fullView;
    private FrameLayout fullscreen;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> chooser;
    private SharedPreferences prefs;
    private CorvoUpdater updater;
    private CancellationSignal signal;
    private static final int FILE_REQUEST=108;
    private static final String HOME="about:blank";
    private static final String HOME_BASE="https://corvo.home/";
    private static final String[] ADS={"doubleclick.net","googlesyndication.com","googleadservices.com",
        "adservice.google.com","adsystem.com","taboola.com","outbrain.com","popads.net",
        "popcash.net","propellerads.com","exoclick.com","trafficjunky.net","adnxs.com",
        "criteo.com","scorecardresearch.com","zedo.com","adsrvr.org"};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        if(privateMode()) WebView.setDataDirectorySuffix("corvo_private");
        prefs=getSharedPreferences("corvo_prefs",MODE_PRIVATE);
        blocked=prefs.getBoolean("adblock",true);
        if(!privateMode()) updater=new CorvoUpdater(this,prefs);
        TextView lock=new TextView(this);
        lock.setText("CORVO\n\nNAVEGADOR PARTICULAR\n\nUse sua impressão digital para entrar.");
        lock.setTextColor(WHITE);lock.setTextSize(21);lock.setGravity(Gravity.CENTER);
        lock.setBackgroundColor(BG);lockedScreen=lock;
        setContentView(lockedScreen);
    }

    @Override protected void onResume(){
        super.onResume();
        if(!unlocked && !authRunning) lockedPrompt();
    }
    @Override protected void onStop(){
        super.onStop();
        if(unlocked){
            unlocked=false;
            if(current!=null) current.onPause();
            setContentView(lockedScreen);
        }
    }
    @Override protected void onDestroy(){
        if(signal!=null) signal.cancel();
        if(updater!=null)updater.release();
        if(privateMode()){
            try {CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();}catch(Exception ignored){}
            for(WebView w:tabs){ try{w.clearHistory();w.clearCache(true);w.destroy();}catch(Exception ignored){} }
        }
        super.onDestroy();
    }

    private void lockedPrompt(){
        authRunning=true;
        try{
            BiometricPrompt prompt=new BiometricPrompt.Builder(this)
                .setTitle("Desbloquear Corvo")
                .setSubtitle("Acesso exclusivo pela biometria")
                .setNegativeButton("Fechar",getMainExecutor(),(dialog,which)->{authRunning=false;finish();})
                .build();
            signal=new CancellationSignal();
            prompt.authenticate(signal,getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){
                    authRunning=false;unlocked=true;
                    if(layout==null) buildUI();
                    setContentView(layout);
                    if(current!=null) current.onResume();
                    if(updater!=null)updater.maybeCheck();
                }
                @Override public void onAuthenticationError(int code,CharSequence msg){
                    authRunning=false;
                    if(!unlocked) finish();
                }
                @Override public void onAuthenticationFailed(){
                    Toast.makeText(BrowserActivity.this,"Digital não reconhecida",Toast.LENGTH_SHORT).show();
                }
            });
        }catch(Exception e){
            authRunning=false;
            new AlertDialog.Builder(this).setTitle("Biometria indisponível")
                .setMessage("Para proteger o navegador, cadastre a biometria nas configurações do Android.")
                .setPositiveButton("Fechar",(d,w)->finish()).setCancelable(false).show();
        }
    }

    private Button btn(String s,Runnable callback){
        Button b=new Button(this);
        b.setText(s);b.setAllCaps(false);b.setTextColor(WHITE);
        b.setTextSize(15);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(6,0,6,0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BAR));
        b.setOnClickListener(v->callback.run());return b;
    }
    private void buildUI(){
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(BG);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setBackgroundColor(BAR);
        address=new EditText(this);address.setTextColor(WHITE);address.setHintTextColor(Color.LTGRAY);
        address.setSingleLine(true);address.setHint("Pesquisar ou digitar URL");address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)->{openAddress();return true;});
        top.addView(address,new LinearLayout.LayoutParams(0,50,1));
        top.addView(btn("IR",this::openAddress),new LinearLayout.LayoutParams(56,50));
        layout.addView(top);
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(BAR);
        nav.addView(btn("◀",()->{if(current!=null && current.canGoBack())current.goBack();}));
        nav.addView(btn("▶",()->{if(current!=null && current.canGoForward())current.goForward();}));
        nav.addView(btn("⟳",()->{if(current!=null)current.reload();}));
        nav.addView(btn("↓",this::findVideos));
        nav.addView(btn("★",this::saveFavorite));
        counter=new TextView(this);counter.setTextColor(BLUE);counter.setTextSize(17);
        counter.setPadding(14,10,14,10);counter.setOnClickListener(v->showTabs());
        nav.addView(counter);
        nav.addView(btn("⋮",this::showMenu));
        layout.addView(nav);
        page=new FrameLayout(this);layout.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        newTab(HOME);
    }
    private boolean isHome(String url) {
        return url!=null && (url.startsWith(HOME_BASE) || url.equals(HOME));
    }
    private void showHome(WebView view){
        if(view==null)return;
        try(InputStream in=getAssets().open("corvo_home.html")){
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)data.write(b,0,n);
            view.loadDataWithBaseURL(HOME_BASE,new String(data.toByteArray(),StandardCharsets.UTF_8),"text/html","UTF-8",null);
        }catch(Exception e){
            view.loadDataWithBaseURL(HOME_BASE,"<html><body style='background:#08101d;color:#e8f7ff;text-align:center;padding-top:35%'><h1>CORVO</h1><p>Navegador Particular</p></body></html>","text/html","UTF-8",null);
        }
        if(view==current && address!=null)address.setText("");
    }
    private boolean retryWithWww(WebView view,Uri url){
        try{
            if(view!=current || url==null || !"https".equalsIgnoreCase(url.getScheme()))return false;
            String host=url.getHost();
            if(host==null || host.startsWith("www.") || host.equals("localhost") ||
               !host.contains(".") || host.contains(":") || Character.isDigit(host.charAt(0)))return false;
            String source=url.toString();
            if(wwwRetries.size()>100)wwwRetries.clear();
            if(!wwwRetries.add(source))return false;
            Uri alternative=url.buildUpon().authority("www."+host+(url.getPort()>0?":"+url.getPort():"")).build();
            view.loadUrl(alternative.toString());
            return true;
        }catch(Exception e){return false;}
    }
    private void showPageError(WebView view,String url,String message){
        if(view!=current || isHome(url) || isFinishing() || !unlocked || errorDialogVisible)return;
        errorDialogVisible=true;
        String site=pageDomains.get(view);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("A página não carregou")
            .setMessage(message+"\n\n"+url)
            .setPositiveButton("Tentar novamente",(d,w)->view.reload())
            .setNeutralButton("Sem bloqueador",(d,w)->{
                if(site!=null){
                    Set<String> exceptions=new HashSet<>(prefs.getStringSet("unblocked_sites",Collections.emptySet()));
                    exceptions.add(site);
                    prefs.edit().putStringSet("unblocked_sites",exceptions).apply();
                }
                view.reload();
            })
            .setNegativeButton("Fechar",null).create();
        dialog.setOnDismissListener(d->errorDialogVisible=false);
        dialog.show();
    }
    private void changeAdBlockForSite(){
        if(current==null)return;
        String site=pageDomains.get(current);
        if(site==null || isHome(current.getUrl())){toast("Abra um site primeiro.");return;}
        Set<String> exceptions=new HashSet<>(prefs.getStringSet("unblocked_sites",Collections.emptySet()));
        if(exceptions.remove(site))toast("Bloqueador reativado neste site.");
        else{exceptions.add(site);toast("Bloqueador desativado apenas neste site.");}
        prefs.edit().putStringSet("unblocked_sites",exceptions).apply();
        current.reload();
    }
    private WebView newTab(String url){
        if(tabs.size()>=15){toast("Limite de 15 abas");return null;}
        WebView w=new WebView(this);
        WebSettings s=w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w,true);
        w.setBackgroundColor(BG);
        w.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String u,android.graphics.Bitmap favicon){
                pageDomains.put(view,Uri.parse(u).getHost());
                if(view==current){address.setText(isHome(u)?"":u);media.clear();}
            }
            @Override public void onPageFinished(WebView view,String u){
                if(view==current)address.setText(isHome(u)?"":u);
                if(!privateMode()&&!isHome(u)&&u.startsWith("http")){
                    prefs.edit().putString("last_url",u).apply();
                }
            }
            @Override public void onLoadResource(WebView view,String u){ trackMedia(u); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri req=request.getUrl();
                if("corvo.home".equalsIgnoreCase(req.getHost()) && "/corvo_icon.webp".equals(req.getPath())){
                    try{
                        return new WebResourceResponse("image/webp",null,getAssets().open("corvo_icon.webp"));
                    }catch(Exception e){return null;}
                }
                if(!blocked || request.isForMainFrame())return null;
                try{
                    String site=pageDomains.get(view);
                    if(site!=null && prefs.getStringSet("unblocked_sites",Collections.emptySet()).contains(site))return null;
                    String h=request.getUrl().getHost().toLowerCase(Locale.ROOT);
                    for(String ad:ADS) if(h.equals(ad)||h.endsWith("."+ad)){
                        return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));
                    }
                }catch(Exception ignored){}
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                String scheme=uri.getScheme();
                if(scheme==null || scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("about"))return false;
                if(!request.isForMainFrame())return true;
                try{
                    Intent i;
                    if(scheme.equalsIgnoreCase("intent")){
                        i=Intent.parseUri(uri.toString(),Intent.URI_INTENT_SCHEME);
                        i.setComponent(null);
                        i.setSelector(null);
                    } else i=new Intent(Intent.ACTION_VIEW,uri);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(i);
                }catch(Exception e){toast("Não foi possível abrir este link.");}
                return true;
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(!request.isForMainFrame())return;
                String url=request.getUrl().toString();
                int code=error.getErrorCode();
                if((code==WebViewClient.ERROR_HOST_LOOKUP || code==WebViewClient.ERROR_CONNECT)
                    && retryWithWww(view,request.getUrl()))return;
                String details=String.valueOf(error.getDescription());
                if(error.getErrorCode()==WebViewClient.ERROR_HOST_LOOKUP)details="O endereço do site não foi encontrado.";
                if(error.getErrorCode()==WebViewClient.ERROR_CONNECT)details="Não foi possível conectar ao servidor.";
                if(error.getErrorCode()==WebViewClient.ERROR_TIMEOUT)details="A conexão demorou demais.";
                showPageError(view,url,details);
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                if(!request.isForMainFrame())return;
                int code=response.getStatusCode();
                if(code==403 || code==429 || code>=500)
                    showPageError(view,request.getUrl().toString(),"O site respondeu HTTP "+code+". Pode estar indisponível ou restringir navegadores incorporados.");
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){
                handler.cancel();
                showPageError(view,error.getUrl(),"Certificado de segurança inválido. A conexão foi interrompida para proteger seus dados.");
            }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
                tabs.remove(view);
                if(view==current){current=null;page.removeView(view);}
                view.destroy();
                if(tabs.isEmpty())newTab(HOME);else switchTab(tabs.size()-1);
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView src,boolean isDialog,boolean isUserGesture,Message message){
                if(!isUserGesture)return false;
                WebView tab=newTab(null);
                if(tab==null)return false;
                ((WebView.WebViewTransport)message.obj).setWebView(tab);
                message.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window){
                if(!tabs.contains(window))return;
                if(window==current){closeTab();return;}
                tabs.remove(window);
                window.destroy();
                if(counter!=null)counter.setText("▣ "+tabs.size());
            }
            @Override public void onProgressChanged(WebView v,int progress){
                if(v==current && progress==100) address.setText(isHome(v.getUrl())?"":v.getUrl());
            }
            @Override public void onShowCustomView(View view,CustomViewCallback cb){
                if(fullView!=null){cb.onCustomViewHidden();return;}
                fullView=view;customCallback=cb;fullscreen=new FrameLayout(BrowserActivity.this);
                fullscreen.setBackgroundColor(Color.BLACK);
                fullscreen.addView(view,new FrameLayout.LayoutParams(-1,-1));
                setContentView(fullscreen);
                getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
            }
            @Override public void onHideCustomView(){hideVideo();}
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(chooser!=null)chooser.onReceiveValue(null);
                chooser=callback;
                try{
                    Intent i=params.createIntent();
                    startActivityForResult(i,FILE_REQUEST);
                    return true;
                }catch(Exception e){chooser=null;return false;}
            }
        });
        w.setDownloadListener(new DownloadListener(){
            @Override public void onDownloadStart(String u,String agent,String disposition,String mime,long len){
                download(u,disposition,mime,agent);
            }
        });
        tabs.add(w);switchTab(tabs.size()-1);
        if(url!=null){if(isHome(url))showHome(w);else w.loadUrl(url);}
        return w;
    }
    private void switchTab(int i){
        if(i<0||i>=tabs.size())return;
        page.removeAllViews();current=tabs.get(i);
        ViewGroup p=(ViewGroup)current.getParent();if(p!=null)p.removeView(current);
        page.addView(current,new FrameLayout.LayoutParams(-1,-1));
        address.setText(isHome(current.getUrl())?"":(current.getUrl()==null?"":current.getUrl()));
        counter.setText("▣ "+tabs.size());
    }
    private void showTabs(){
        if(tabs.isEmpty())return;
        String[] names=new String[tabs.size()];
        for(int i=0;i<tabs.size();i++){
            String title=tabs.get(i).getTitle();
            names[i]=(i+1)+". "+(title==null?"Nova aba":title);
        }
        new AlertDialog.Builder(this).setTitle("Abas abertas")
            .setItems(names,(d,which)->switchTab(which))
            .setPositiveButton("+ Nova aba",(d,w)->newTab(HOME))
            .setNeutralButton("Fechar aba atual",(d,w)->closeTab())
            .setNegativeButton("Cancelar",null).show();
    }
    private void closeTab(){
        if(current==null)return;
        int ix=tabs.indexOf(current);WebView old=current;
        page.removeView(old);tabs.remove(old);old.destroy();current=null;
        if(tabs.isEmpty())newTab(HOME);
        else switchTab(Math.max(0,ix-1));
    }
    private void openAddress(){
        if(current==null)return;
        String x=address.getText().toString().trim();
        if(x.isEmpty())return;
        if(!x.matches("(?i)^https?://.*")){
            if(x.matches("(?i)^[^\\s]+\\.[^\\s]+.*"))x="https://"+x;
            else{
                try{x="https://www.google.com/search?q="+URLEncoder.encode(x,"UTF-8");}
                catch(Exception e){return;}
            }
        }
        current.loadUrl(x);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
        address.clearFocus();
    }
    private void showMenu(){
        String[] opts={"+ Nova aba","Janela privada","Downloads do Android",
            "Favoritos","Histórico recente",
            blocked?"Desativar bloqueador":"Ativar bloqueador",
            "Compartilhar página","Página inicial","Limpar dados","Verificar atualizações","Bloqueador deste site"};
        new AlertDialog.Builder(this).setTitle("Corvo Navegador")
            .setItems(opts,(d,n)->{
                switch(n){
                    case 0:newTab(HOME);break;
                    case 1:startActivity(new Intent(this,PrivateActivity.class));break;
                    case 2:startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));break;
                    case 3:showFavorites();break;
                    case 4:if(!privateMode())showHistory();else toast("Modo privado");break;
                    case 5:blocked=!blocked;prefs.edit().putBoolean("adblock",blocked).apply();
                        toast(blocked?"Bloqueador ativado":"Bloqueador desativado");break;
                    case 6:if(current!=null){
                        Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_TEXT,current.getUrl());
                        startActivity(Intent.createChooser(share,"Compartilhar"));}
                        break;
                    case 7:showHome(current);break;
                    case 8:new AlertDialog.Builder(this).setTitle("Apagar dados de navegação?")
                        .setPositiveButton("Limpar",(dd,nn)->{
                            for(WebView w:tabs){w.clearHistory();w.clearCache(true);}
                            CookieManager.getInstance().removeAllCookies(null);
                            if(!privateMode())prefs.edit().remove("history").remove("last_url").apply();
                            toast("Dados locais apagados");
                        }).setNegativeButton("Cancelar",null).show();break;
                    case 9:if(updater!=null)updater.check(true);else toast("Abra a janela principal para verificar atualizações");break;
                    case 10:changeAdBlockForSite();break;
            }
            }).show();
    }
    private void saveFavorite(){
        if(current==null)return;
        String u=current.getUrl();if(u==null || isHome(u)){toast("Abra um site para adicionar aos favoritos");return;}
        Set<String> s=new LinkedHashSet<>(prefs.getStringSet("bookmarks",Collections.emptySet()));
        s.add(u);prefs.edit().putStringSet("bookmarks",s).apply();toast("Favorito salvo");
    }
    private void showFavorites(){
        Set<String> s=prefs.getStringSet("bookmarks",Collections.emptySet());
        String[] items=s.toArray(new String[0]);
        if(items.length==0){toast("Nenhum favorito salvo");return;}
        new AlertDialog.Builder(this).setTitle("Favoritos").setItems(items,(d,i)->current.loadUrl(items[i]))
            .setNeutralButton("Apagar favoritos",(d,i)->prefs.edit().remove("bookmarks").apply())
            .setNegativeButton("Fechar",null).show();
    }
    private void showHistory(){
        String u=prefs.getString("last_url","");
        if(u.isEmpty()){toast("Histórico vazio");return;}
        new AlertDialog.Builder(this).setTitle("Última página")
            .setMessage(u).setPositiveButton("Abrir",(d,w)->current.loadUrl(u))
            .setNegativeButton("Cancelar",null).show();
    }
    private void trackMedia(String u){
        if(u==null||u.length()>2400)return;
        String t=u.toLowerCase(Locale.ROOT);
        if(t.matches("(?s).*\\.(mp4|webm|m3u8|mp3|m4a|mov|mkv)(\\?.*)?$")){
            runOnUiThread(()->{if(media.size()<80)media.add(u);});
        }
    }
    private void findVideos(){
        if(current==null)return;
        // Collect HTML5 media and links in addition to network resources.
        String js="(function(){var a=[];function put(u){if(u&&/^https?:/i.test(u)&&a.indexOf(u)<0)a.push(u);}"
            +"document.querySelectorAll('video,audio,source').forEach(function(e){put(e.src);put(e.currentSrc);});"
            +"document.querySelectorAll('a[href]').forEach(function(e){if(/\\.(mp4|webm|m3u8|mp3)(\\?|$)/i.test(e.href))put(e.href);});"
            +"return a.slice(0,70);})()";
        current.evaluateJavascript(js,(result)->{
            LinkedHashSet<String> all=new LinkedHashSet<>(media);
            try{JSONArray a=new JSONArray(result);for(int i=0;i<a.length();i++){
                String u=a.optString(i);if(u.startsWith("https://")||u.startsWith("http://"))all.add(u);
            }}catch(Exception ignored){}
            if(all.isEmpty()){new AlertDialog.Builder(this).setTitle("Procurar vídeos")
                .setMessage("Nenhum endereço direto detectado. Inicie a reprodução e toque novamente em ↓.")
                .setPositiveButton("OK",null).show();return;}
            List<String> list=new ArrayList<>(all);
            String[] labels=new String[list.size()];
            for(int i=0;i<list.size();i++){
                String s=list.get(i); labels[i]=s.length()>90?s.substring(0,90)+"…":s;
            }
            new AlertDialog.Builder(this).setTitle("Mídias detectadas ("+list.size()+")")
                .setItems(labels,(d,i)->mediaOptions(list.get(i)))
                .setNegativeButton("Fechar",null).show();
        });
    }
    private void mediaOptions(String u){
        new AlertDialog.Builder(this).setTitle("Arquivo de mídia").setMessage(u)
            .setPositiveButton("Baixar",(d,w)->download(u,null,null,current==null?null:current.getSettings().getUserAgentString()))
            .setNeutralButton("Abrir",(d,w)->{if(current!=null)current.loadUrl(u);})
            .setNegativeButton("Cancelar",null).show();
    }
    private void download(String u,String disposition,String mime,String agent){
        try{
            if(!u.startsWith("http")){toast("Este tipo de endereço não é baixável diretamente");return;}
            String name=URLUtil.guessFileName(u,disposition,mime);
            if(name==null||name.trim().isEmpty())name="corvo-download";
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(u));
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            String cookies=CookieManager.getInstance().getCookie(u);
            if(cookies!=null)req.addRequestHeader("Cookie",cookies);
            if(agent!=null)req.addRequestHeader("User-Agent",agent);
            if(current!=null&&current.getUrl()!=null)req.addRequestHeader("Referer",current.getUrl());
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            toast("Download iniciado");
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Download não iniciado")
            .setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
    }
    private void hideVideo(){
        if(fullView==null)return;
        getWindow().getDecorView().setSystemUiVisibility(0);
        if(fullscreen!=null)fullscreen.removeAllViews();
        fullView=null;fullscreen=null;
        setContentView(layout);
        if(customCallback!=null){customCallback.onCustomViewHidden();customCallback=null;}
    }
    @Override protected void onActivityResult(int r,int code,Intent data){
        super.onActivityResult(r,code,data);
        if(r==FILE_REQUEST&&chooser!=null){
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(code,data));chooser=null;
        }
    }
    @Override public void onBackPressed(){
        if(fullView!=null){hideVideo();return;}
        if(current!=null&&current.canGoBack()){current.goBack();return;}
        if(tabs.size()>1){closeTab();return;}
        super.onBackPressed();
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}

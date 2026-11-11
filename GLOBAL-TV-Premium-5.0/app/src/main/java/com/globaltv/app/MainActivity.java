package com.globaltv.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.util.TypedValue;
import android.text.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

/** GLOBAL TV Premium - Android TV / Google TV. */
public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_PLAYLIST="https://iptv-org.github.io/iptv/index.m3u";
    private static final int BG=0xFF080D19, PANEL=0xFF111A2B, CARD=0xFF17233A, MUTED=0xFF91A0BA, ACCENT=0xFF746BFF, CYAN=0xFF27C8C0, FOCUS_GOLD=0xFFFFB52E, FOCUS_RED=0xFFFF654F;
    // Pool borne pour eviter une file de taches sans limite sur les box peu puissantes.
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(12),
        r->{Thread t=new Thread(r,"globaltv-io");t.setPriority(Thread.NORM_PRIORITY-1);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private volatile boolean destroyed=false;
    private int catalogRequestId=0;
    private static final int MAX_CATALOG_ITEMS=20000;
    private int maxRenderItems=120;
    private String activeProfile="Adulte";
    private static final String PROFILE_ADULT="Adulte", PROFILE_CHILD="Enfant";
    private final ArrayList<MediaEntry> all=new ArrayList<>();
    // Catalogue isolé réservé aux sources MTV que l'utilisateur est autorisé à utiliser.
    private final ArrayList<MediaEntry> mtvItems=new ArrayList<>();
    private String mtvPlaylistUrl="";
    private final Set<String> favorites=new HashSet<>();
    private final Set<String> historyKeysCache=new HashSet<>();
    private final Map<String,String> epgNow=new ConcurrentHashMap<>();
    private SharedPreferences prefs;
    private String epgUrl="";
    private final LinkedHashSet<String> playlistSources=new LinkedHashSet<>();
    private String lastPlayedUrl="";
    private long pendingResumePosition=0L;
    private LinearLayout root,sidebar,content,list,chips,playerToolbar,playerArea,webContainer;
    private final Map<String,TextView> navViews=new LinkedHashMap<>();
    private View webCustomView;
    private WebChromeClient.CustomViewCallback webCustomViewCallback;
    private FrameLayout webFullscreenContainer;
    private int webOriginalSystemUiVisibility=0;
    private ScrollView sidebarScroll,catalogScroll;
    private HorizontalScrollView chipScroll;
    private WebView mtvWebView;
    private String activeWebUrl="";
    private boolean playerExpanded=false;
    private boolean playerFullscreen=false;
    private View playerFullscreenTop,playerFullscreenChips,playerFullscreenStatus,playerFullscreenFooter;
    private MediaEntry currentEntry;
    private final Set<String> failedInCurrentRun=new HashSet<>();
    private EditText search;
    private TextView status,heading,sourceLabel;
    private PlayerView playerView;
    private ExoPlayer player;
    private String section="ACCUEIL",activeFilter="Tout",sourceMode="M3U",xtreamBase="",xtreamUser="",xtreamPass="";

    static class MediaEntry {
        String name,url,group,logo,type,id,container,tvgId;
        MediaEntry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;id="";container="m3u8";tvgId="";}
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);prefs=getPreferences(0);
        activeProfile=prefs.getString("active_profile",PROFILE_ADULT);
        configureMemoryBudget();
        favorites.addAll(prefs.getStringSet("favorites",new HashSet<>()));playlistSources.addAll(prefs.getStringSet("m3u_sources",new LinkedHashSet<>()));epgUrl=prefs.getString("epg_url","");mtvPlaylistUrl=prefs.getString("mtv_playlist_url","");lastPlayedUrl=prefs.getString("last_url","");buildUi();loadPlaylist(playlistSources.isEmpty()?DEFAULT_PLAYLIST:playlistSources.iterator().next());if(!epgUrl.isEmpty())loadEpg(epgUrl);}
    // Adapte le nombre de cartes rendues à la classe mémoire réelle de l'appareil.
    // Le catalogue reste complet en mémoire (plafonné), seule la vue est limitée.
    private void configureMemoryBudget(){
        ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        int memoryClass=am!=null?am.getMemoryClass():128;
        boolean lowRam=am!=null && am.isLowRamDevice();
        if(lowRam || memoryClass<=128) maxRenderItems=45;
        else if(memoryClass<=256) maxRenderItems=80;
        else if(memoryClass<=384) maxRenderItems=120;
        else maxRenderItems=180;
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void buildUi(){
        root=row();root.setBackgroundColor(BG);setContentView(root);
        sidebarScroll=new ScrollView(this);sidebarScroll.setFillViewport(true);sidebarScroll.setVerticalScrollBarEnabled(true);sidebarScroll.setScrollbarFadingEnabled(false);sidebarScroll.setSmoothScrollingEnabled(true);sidebarScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);root.addView(sidebarScroll,new LinearLayout.LayoutParams(dp(220),-1));
        sidebar=column();sidebar.setPadding(dp(18),dp(22),dp(12),dp(14));sidebar.setBackgroundColor(0xFF0D1525);sidebarScroll.addView(sidebar,new ScrollView.LayoutParams(-1,-2));
        TextView brand=text("MTv",31,0xFF35E6FF,true);brand.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC));brand.setLetterSpacing(0.035f);brand.setShadowLayer(dp(9),0,0,0xFF8A4DFF);sidebar.addView(brand,new LinearLayout.LayoutParams(-1,dp(58)));
        addNav("⌂   Accueil","ACCUEIL");addNav("▣   TV en direct","TV EN DIRECT");addNav("♫   Musique","MUSIQUE");addNav("▤   Infos","INFOS");addNav("▶   Films","FILMS");addNav("▤   Séries","SÉRIES");addNav("✦   Animés","ANIMÉS");addNav("♡   Mes favoris","FAVORIS");addNav("◷   Historique","HISTORIQUE");addNav("▦   Guide TV","GUIDE TV");addNav("🌐   AOXTv","MTV");addNav("🌐   WiTv","WITV");addNav("🌐   FrSTv","FRSTV");addNav("🌐   FsLive","FSLIVE");addNav("🌐   EpiXtv","EPI XTV");addNav("🌐   DudeTV","DUDETV");addNav("🌐   Braflix","BRAFLIX");
        View spacer=new View(this);sidebar.addView(spacer,new LinearLayout.LayoutParams(1,dp(12)));
        sourceLabel=text("SOURCE : M3U",10,CYAN,true);sidebar.addView(sourceLabel,new LinearLayout.LayoutParams(-1,dp(28)));
        addNav("⚙   Sources & paramètres","PARAMÈTRES");
        content=column();content.setPadding(dp(25),dp(18),dp(25),dp(14));root.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout top=row();content.addView(top,new LinearLayout.LayoutParams(-1,dp(58)));
        heading=text("",1,Color.TRANSPARENT,false);heading.setVisibility(View.GONE);top.addView(heading,new LinearLayout.LayoutParams(0,0));
        search=new EditText(this);search.setId(View.generateViewId());search.setSingleLine(true);search.setTextSize(14);search.setHintTextColor(MUTED);search.setTextColor(Color.WHITE);search.setHint("⌕  Rechercher un contenu…");search.setPadding(dp(14),0,dp(14),0);search.setBackground(bg(CARD,12));top.addView(search,new LinearLayout.LayoutParams(0,dp(44),1f));
        // Configure le focus D-pad uniquement après la création du champ de recherche.
        for(int i=0;i<sidebar.getChildCount();i++){
            View v=sidebar.getChildAt(i);
            if(v.isFocusable()) v.setNextFocusRightId(search.getId());
        }
        search.setNextFocusLeftId(sidebar.getChildAt(4).getId());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
                pendingSearch=()->{if(!destroyed)renderList();};
                mainHandler.postDelayed(pendingSearch,180);
            }
            public void afterTextChanged(Editable e){}
        });
        chipScroll=new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(true);
        chipScroll.setScrollbarFadingEnabled(false);
        chipScroll.setSmoothScrollingEnabled(true);
        chipScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        chipScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        chips=row();chips.setPadding(0,dp(12),0,dp(9));
        chipScroll.addView(chips,new HorizontalScrollView.LayoutParams(-2,-1));
        content.addView(chipScroll,new LinearLayout.LayoutParams(-1,dp(54)));
        status=text("Préparation de votre catalogue…",12,MUTED,false);content.addView(status,new LinearLayout.LayoutParams(-1,dp(30)));
        catalogScroll=new ScrollView(this);
        catalogScroll.setFillViewport(true);
        catalogScroll.setVerticalScrollBarEnabled(true);
        catalogScroll.setScrollbarFadingEnabled(false);
        catalogScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        catalogScroll.setSmoothScrollingEnabled(true);
        catalogScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        playerArea=row();playerArea.setGravity(Gravity.TOP);content.addView(playerArea,new LinearLayout.LayoutParams(-1,0,1));
        playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,-1));
        list=column();list.setPadding(0,dp(4),0,dp(12));catalogScroll.addView(list);
        TextView foot=text("GLOBAL TV • Les flux et catalogues dépendent des droits, de la disponibilité et des régions.",10,0xFF65738D,false);foot.setId(View.generateViewId());content.addView(foot,new LinearLayout.LayoutParams(-1,dp(26)));
        updateChips();
    }
    private void addNav(String label,String target){TextView v=text(label,14,0xFFD9E1F2,true);v.setPadding(dp(13),0,dp(8),0);v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0x00172035,10));navViews.put(target,v);
        v.setOnFocusChangeListener((view,hasFocus)->refreshNavStyle(target,hasFocus));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.bottomMargin=dp(3);sidebar.addView(v,p);v.setOnClickListener(w->{if(isAdultWebSection(target)&&PROFILE_CHILD.equals(activeProfile)){Toast.makeText(this,"Cette rubrique est réservée au profil Adulte.",Toast.LENGTH_LONG).show();return;}if(isProtectedSection(target)&&!prefs.getString("parental_pin_hash","").isEmpty()){requestParentalAccess(target);return;}navigateTo(target);});refreshNavStyle(target,v.hasFocus());}
    private void refreshNavStyle(String target,boolean focused){TextView v=navViews.get(target);if(v==null)return;boolean selected=section.equals(target);if(selected){GradientDrawable d=bg(0xFF6E54FF,10);d.setStroke(dp(2),0xFF42E8F5);v.setBackground(d);v.setTextColor(Color.WHITE);}else if(focused){GradientDrawable d=bg(FOCUS_GOLD,10);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);v.setTextColor(0xFF17111A);}else{v.setBackground(bg(0x00172035,10));v.setTextColor(0xFFD9E1F2);}}
    private void refreshAllNavStyles(){for(String target:navViews.keySet())refreshNavStyle(target,navViews.get(target).hasFocus());}
    private void navigateTo(String target){
        if(playerFullscreen)togglePlayerExpanded();
        section=target;refreshAllNavStyles();activeFilter="Tout";search.setHint("⌕  Rechercher…");updateChips();
        if(isWebSection(target)){showSiteWebView(webUrlFor(target));return;}
        hideMtvWebView();renderList();
        if(target.equals("PARAMÈTRES"))showSettings();
        else if(search!=null&&!search.hasFocus())search.requestFocus();
    }
    private void updateChips(){if(chips==null)return;chips.removeAllViews();String[] opts;if(section.equals("TV EN DIRECT"))opts=new String[]{"Tout","France","Afrique","USA","Canada","Germany","Espagne","Angleterre","Sports","News","Music","Kids","Réligion","Documentaire"};else if(section.equals("FAVORIS")||section.equals("HISTORIQUE")||section.equals("GUIDE TV")||section.equals("MUSIQUE")||section.equals("INFOS"))opts=new String[]{"Tout"};else opts=new String[]{"Tout","Action","Comédie","Drame","Aventure","Fantasy","Science-fiction","Animation"};int previousId=View.NO_ID;for(String value:opts){final String filter=value;TextView c=text(value,12,activeFilter.equals(value)?Color.WHITE:0xFFB8C4D8,true);c.setGravity(Gravity.CENTER);c.setPadding(dp(15),0,dp(15),0);c.setBackground(bg(activeFilter.equals(value)?ACCENT:CARD,18));c.setFocusable(true);c.setFocusableInTouchMode(true);c.setClickable(true);c.setId(View.generateViewId());c.setNextFocusUpId(search.getId());if(previousId!=View.NO_ID)c.setNextFocusLeftId(previousId);if(previousId!=View.NO_ID)chips.getChildAt(chips.getChildCount()-1).setNextFocusRightId(c.getId());previousId=c.getId();c.setOnFocusChangeListener((v,focus)->{if(focus){if(filter.equals(activeFilter)){GradientDrawable d=bg(ACCENT,18);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);v.setTextColor(Color.WHITE);}else{GradientDrawable d=bg(FOCUS_GOLD,18);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);v.setTextColor(0xFF17111A);}Rect rect=new Rect();v.getDrawingRect(rect);chipScroll.requestChildRectangleOnScreen(v,rect,true);}else{v.setBackground(bg(filter.equals(activeFilter)?ACCENT:CARD,18));v.setTextColor(filter.equals(activeFilter)?Color.WHITE:0xFFB8C4D8);}});c.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){v.performClick();return true;}if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_DPAD_LEFT){int idx=chips.indexOfChild(v),next=idx+(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);if(next>=0&&next<chips.getChildCount()){chips.getChildAt(next).requestFocus();return true;}}if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN){if(list.getChildCount()>0){list.getChildAt(0).requestFocus();return true;}}return false;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.rightMargin=dp(8);chips.addView(c,p);c.setOnClickListener(v->{activeFilter=filter;updateChips();renderList();});}search.setNextFocusDownId(chips.getChildAt(0).getId());}


    private void executeIo(Runnable task){
        if(destroyed)return;
        try{io.execute(task);}catch(RejectedExecutionException ex){
            if(!destroyed)postUi(()->Toast.makeText(this,"Trop d’operations en cours. Reessaie dans un instant.",Toast.LENGTH_LONG).show());
        }
    }
    private void postUi(Runnable task){
        if(!destroyed)mainHandler.post(()->{if(!destroyed&&!isFinishing())task.run();});
    }
    private void releasePlayer(){
        if(playerFullscreen){togglePlayerExpanded();}
        if(playerExpanded){sidebar.setVisibility(View.VISIBLE);for(int i=0;i<content.getChildCount();i++)content.getChildAt(i).setVisibility(View.VISIBLE);playerExpanded=false;}
        if(playerView!=null){playerView.setPlayer(null);if(playerView.getParent() instanceof ViewGroup)((ViewGroup)playerView.getParent()).removeView(playerView);playerView=null;}
        if(catalogScroll!=null&&catalogScroll.getParent()!=playerArea)playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,-1));
        if(playerToolbar!=null){if(content!=null)content.removeView(playerToolbar);playerToolbar=null;}
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
    }
    private HttpURLConnection open(String address)throws Exception{URL u=new URL(address);HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","GLOBAL-TV-Android/5.0");
        c.setRequestProperty("Accept","*/*");
        c.setInstanceFollowRedirects(true);
        return c;}
    private String get(String address)throws Exception{HttpURLConnection c=open(address);try{int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(code<200||code>=300)throw new IOException("Serveur HTTP "+code);if(in==null)throw new IOException("Réponse vide du serveur");
            try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n,total=0;
                while((n=stream.read(b))!=-1){total+=n;if(total>15*1024*1024)throw new IOException("Réponse trop volumineuse (limite 15 Mo)");out.write(b,0,n);}
                return out.toString("UTF-8");
            }}finally{c.disconnect();}}
    private void loadPlaylist(String url){if(url!=null&&(url.startsWith("http://")||url.startsWith("https://"))){playlistSources.add(url);prefs.edit().putStringSet("m3u_sources",new HashSet<>(playlistSources)).apply();}loadAllPlaylists();}
    private void loadAllPlaylists(){final int request=++catalogRequestId;ArrayList<String> urls=new ArrayList<>(playlistSources);if(urls.isEmpty())urls.add(DEFAULT_PLAYLIST);status.setText("Chargement de "+urls.size()+" source(s) M3U…");executeIo(()->{ArrayList<MediaEntry> merged=new ArrayList<>();ArrayList<String> errors=new ArrayList<>();for(String address:urls){if(merged.size()>=MAX_CATALOG_ITEMS)break;try{String data=get(address);BufferedReader r=new BufferedReader(new StringReader(data));String line,name=null,group="Autres",logo="",tvgId="";int count=0;while((line=r.readLine())!=null&&merged.size()<MAX_CATALOG_ITEMS){line=line.trim();if(line.startsWith("#EXTINF:")){name=line.substring(line.lastIndexOf(',')+1).trim();group="Autres";logo="";tvgId="";int g=line.indexOf("group-title=\"");if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}int tid=line.indexOf("tvg-id=\"");if(tid>=0){int te=line.indexOf('"',tid+8);if(te>tid)tvgId=line.substring(tid+8,te);}int l=line.indexOf("tvg-logo=\"");if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}}else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){if(line.startsWith("https://")||line.startsWith("http://")){MediaEntry entry=new MediaEntry(name,line,group,logo,"m3u");entry.tvgId=tvgId;merged.add(entry);count++;}name=null;tvgId="";}}}catch(Exception ex){errors.add(Uri.parse(address).getHost()+": "+ex.getMessage());}}final int loaded=merged.size();postUi(()->{if(request!=catalogRequestId)return;if(loaded==0&&!errors.isEmpty()){status.setText("Erreur M3U : "+errors.get(0));return;}all.clear();all.addAll(merged);sourceMode="M3U";sourceLabel.setText("SOURCES : "+urls.size()+" M3U");status.setText(loaded+" chaînes/contenus chargés • "+urls.size()+" source(s) M3U"+(errors.isEmpty()?"":" • "+errors.size()+" erreur(s)"));renderList();});});}

    private String api(String action,String extra){String base=xtreamBase;if(!base.endsWith("/"))base+="/";return base+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass)+"&action="+action+(extra==null?"":extra);}
    private String enc(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private void loadXtream(){final int request=++catalogRequestId;status.setText("Connexion au serveur Xtream Codes…");executeIo(()->{ArrayList<MediaEntry> parsed=new ArrayList<>();String err=null;try{String auth=get(xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass));JSONObject root=new JSONObject(auth);JSONObject user=root.optJSONObject("user_info");if(user!=null&&!user.optString("auth","1").equals("1"))throw new IOException("Identifiants refusés par le serveur");
                Map<String,String> liveCats=safeCategories("get_live_categories");Map<String,String> vodCats=safeCategories("get_vod_categories");Map<String,String> seriesCats=safeCategories("get_series_categories");
                parseXtreamArray(parsed,get(api("get_live_streams","")),"live","m3u8",liveCats);
                try{parseXtreamArray(parsed,get(api("get_vod_streams","")),"movie","mp4",vodCats);}catch(Exception ignored){}
                try{parseXtreamArray(parsed,get(api("get_series","")),"series","mp4",seriesCats);}catch(Exception ignored){}
            }catch(Exception e){err=e.getMessage();}final String error=err;postUi(()->{if(request!=catalogRequestId)return;if(error!=null){status.setText("Connexion Xtream impossible : "+error);return;}all.clear();all.addAll(parsed);sourceMode="XTREAM";sourceLabel.setText("SOURCE : XTREAM");status.setText(parsed.size()+" éléments récupérés • Serveur Xtream");renderList();});});}
    private Map<String,String> safeCategories(String action){try{return parseCategories(get(api(action,"")));}catch(Exception ignored){return new HashMap<>();}}
    private Map<String,String> parseCategories(String json){Map<String,String> map=new HashMap<>();try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)map.put(o.optString("category_id"),o.optString("category_name","Autres"));}}catch(Exception ignored){}return map;}
    private void parseXtreamArray(ArrayList<MediaEntry> out,String json,String type,String fallback,Map<String,String> categories){try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length()&&out.size()<MAX_CATALOG_ITEMS;i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString(type.equals("series")?"series_id":"stream_id",o.optString("id",""));if(id.isEmpty())continue;String name=o.optString("name",type.equals("series")?"Série":"Contenu");String cat=o.optString("category_name",(categories.containsKey(o.optString("category_id"))?categories.get(o.optString("category_id")):(type.equals("live")?"TV en direct":type.equals("movie")?"Films":"Séries")));String logo=o.optString("stream_icon",o.optString("cover",""));String ext=o.optString("container_extension",fallback);String path=xtreamBase+(xtreamBase.endsWith("/")?"":"/")+type+"/"+enc(xtreamUser)+"/"+enc(xtreamPass)+"/"+id+"."+ext;MediaEntry m=new MediaEntry(name,path,cat,logo,type);m.id=id;m.container=ext;m.tvgId=o.optString("epg_channel_id","");out.add(m);}}catch(Exception ignored){}}

    private boolean hasHistory(MediaEntry c){if(c==null||c.url==null||c.url.isEmpty())return false;try{return historyKeysCache.contains("history_"+key(c.url));}catch(Exception ex){return false;}}
    private long historyPosition(MediaEntry c){if(c==null||c.url==null||prefs==null)return 0L;try{Object value=prefs.getAll().get("position_"+key(c.url));return value instanceof Long?Math.max(0L,(Long)value):0L;}catch(Exception ex){return 0L;}}
    private boolean matchSection(MediaEntry c){
        if(c==null||c.url==null)return false;
        if(section.equals("MTV")) return mtvItems.contains(c);
        if(section.equals("MUSIQUE"))return isMusicEntry(c);
        if(section.equals("INFOS"))return isNewsEntry(c);
        String g=c.group==null?"":c.group.toLowerCase(Locale.ROOT),n=c.name==null?"":c.name.toLowerCase(Locale.ROOT);if(section.equals("FAVORIS"))return favorites.contains(c.url);if(section.equals("HISTORIQUE"))return hasHistory(c);if(section.equals("GUIDE TV"))return (c.type.equals("live")||c.type.equals("m3u"))&&!c.tvgId.isEmpty();if(section.equals("TV EN DIRECT"))return c.type.equals("live")||c.type.equals("m3u");if(section.equals("FILMS"))return c.type.equals("movie")||g.contains("movie")||g.contains("film")||g.contains("vod");if(section.equals("SÉRIES"))return c.type.equals("series")||g.contains("series");if(section.equals("ANIMÉS"))return g.contains("anime")||g.contains("animation")||g.contains("cartoon")||n.contains("anime");return true;}
    private boolean filterMatches(String filter,String group,String name){
        if(filter==null||filter.equals("Tout"))return true;
        String value=((group==null?"":group)+" "+(name==null?"":name)).toLowerCase(Locale.ROOT);
        String f=filter.toLowerCase(Locale.ROOT);
        if(f.equals("usa"))return value.contains("usa")||value.contains("united states")||value.contains("u.s.")||value.contains("america");
        if(f.equals("canada"))return value.contains("canada");
        if(f.equals("germany"))return value.contains("germany")||value.contains("deutschland")||value.contains("allemagne");
        if(f.equals("espagne"))return value.contains("spain")||value.contains("espagne")||value.contains("españa");
        if(f.equals("angleterre"))return value.contains("england")||value.contains("united kingdom")||value.contains("uk ")||value.contains("britain")||value.contains("royaume-uni");
        if(f.equals("réligion")||f.equals("religion"))return value.contains("relig")||value.contains("gospel")||value.contains("church")||value.contains("islam")||value.contains("christ")||value.contains("faith");
        if(f.equals("documentaire"))return value.contains("documentary")||value.contains("documentaire")||value.contains("discovery")||value.contains("history")||value.contains("nature");
        return value.contains(f);
    }
    private void stylePlayerButton(TextView button,boolean focused){
        if(focused){GradientDrawable d=bg(FOCUS_GOLD,10);d.setStroke(dp(2),FOCUS_RED);button.setBackground(d);button.setTextColor(0xFF17111A);button.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110).start();}
        else{button.setBackground(bg(CARD,10));button.setTextColor(Color.WHITE);button.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}
    }
    private TextView playerControl(String glyph,String description,Runnable action){
        TextView button=text(glyph,19,Color.WHITE,true);button.setGravity(Gravity.CENTER);button.setContentDescription(description);button.setFocusable(true);button.setClickable(true);button.setBackground(bg(CARD,10));
        button.setOnFocusChangeListener((v,focused)->stylePlayerButton((TextView)v,focused));button.setOnClickListener(v->{if(action!=null)action.run();});return button;
    }
    private void renderHome(){
        list.removeAllViews();
        TextView eyebrow=text("BIENVENUE SUR",11,CYAN,true);eyebrow.setLetterSpacing(0.16f);list.addView(eyebrow,new LinearLayout.LayoutParams(-1,dp(24)));
        TextView title=text("MTv",34,Color.WHITE,true);title.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC));title.setShadowLayer(dp(10),0,0,0xFF765BFF);list.addView(title,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView subtitle=text("Tout votre divertissement, au même endroit.",14,MUTED,false);list.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout hero=column();GradientDrawable heroBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF33205F,0xFF154A61,0xFF10243B});heroBg.setCornerRadius(dp(18));hero.setBackground(heroBg);hero.setPadding(dp(22),dp(16),dp(18),dp(16));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(128));hp.topMargin=dp(12);hp.bottomMargin=dp(14);list.addView(hero,hp);
        TextView heroTag=text("À LA UNE  •  MTv",10,0xFFFFD36A,true);hero.addView(heroTag,new LinearLayout.LayoutParams(-1,dp(22)));
        TextView heroTitle=text("Vos chaînes et programmes préférés",20,Color.WHITE,true);hero.addView(heroTitle,new LinearLayout.LayoutParams(-1,dp(38)));
        TextView heroDetail=text(all.size()+" contenus disponibles  •  "+playlistSources.size()+" source(s) M3U",12,0xFFE0E8F5,false);hero.addView(heroDetail,new LinearLayout.LayoutParams(-1,dp(24)));
        TextView catTitle=text("EXPLORER LES RUBRIQUES",12,0xFFFFC45C,true);catTitle.setLetterSpacing(0.08f);list.addView(catTitle,new LinearLayout.LayoutParams(-1,dp(28)));
        HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);LinearLayout categoryRow=row();categoryScroll.addView(categoryRow);list.addView(categoryScroll,new LinearLayout.LayoutParams(-1,dp(54)));
        String[][] quick={{"TV EN DIRECT","TV EN DIRECT"},{"FILMS","FILMS"},{"SÉRIES","SÉRIES"},{"ANIMÉS","ANIMÉS"},{"MUSIQUE","MUSIQUE"},{"GUIDE TV","GUIDE TV"}};
        for(String[] item:quick){TextView tile=text(item[0],12,Color.WHITE,true);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(16),0,dp(16),0);tile.setFocusable(true);tile.setClickable(true);tile.setBackground(bg(CARD,12));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,dp(40));tp.rightMargin=dp(9);categoryRow.addView(tile,tp);tile.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(FOCUS_GOLD,12);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);((TextView)v).setTextColor(0xFF17111A);v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110).start();}else{v.setBackground(bg(CARD,12));((TextView)v).setTextColor(Color.WHITE);v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});tile.setOnClickListener(v->{navigateTo(item[1]);});}
        TextView featuredTitle=text("CHAÎNES À DÉCOUVRIR",12,0xFFFFC45C,true);featuredTitle.setLetterSpacing(0.08f);LinearLayout.LayoutParams ft=new LinearLayout.LayoutParams(-1,dp(34));ft.topMargin=dp(8);list.addView(featuredTitle,ft);
        HorizontalScrollView featuredScroll=new HorizontalScrollView(this);featuredScroll.setHorizontalScrollBarEnabled(false);LinearLayout featured=row();featuredScroll.addView(featured);list.addView(featuredScroll,new LinearLayout.LayoutParams(-1,dp(154)));
        int count=0;for(MediaEntry entry:all){if(entry==null||entry.name==null||entry.name.isEmpty())continue;LinearLayout card=column();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(dp(8),dp(8),dp(8),dp(6));card.setBackground(bg(PANEL,12));card.setFocusable(true);card.setClickable(true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(132),dp(138));cp.rightMargin=dp(10);featured.addView(card,cp);
            ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackground(bg(CARD,8));card.addView(logo,new LinearLayout.LayoutParams(dp(112),dp(78)));if(entry.logo!=null&&!entry.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(entry.logo).placeholder(bg(CARD,8)).error(bg(CARD,8)).into(logo);
            TextView label=text(entry.name,11,Color.WHITE,true);label.setGravity(Gravity.CENTER);label.setMaxLines(2);card.addView(label,new LinearLayout.LayoutParams(-1,dp(42)));
            card.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(0xFF3A315D,12);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(110).start();}else{v.setBackground(bg(PANEL,12));v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});
            card.setOnClickListener(v->play(entry));if(++count>=12)break;
        }
        if(count==0){TextView empty=text("Les chaînes apparaîtront ici dès que la playlist sera chargée.",12,MUTED,false);list.addView(empty);}
        TextView hint=text("Astuce : utilise les flèches de la télécommande pour parcourir les cartes, puis OK pour ouvrir.",11,MUTED,false);LinearLayout.LayoutParams hintP=new LinearLayout.LayoutParams(-1,dp(34));hintP.topMargin=dp(8);list.addView(hint,hintP);
    }
    private void renderList(){if(list==null)return;list.removeAllViews();if(section.equals("HISTORIQUE")){historyKeysCache.clear();try{for(String prefKey:prefs.getAll().keySet())if(prefKey.startsWith("history_"))historyKeysCache.add(prefKey);}catch(Exception ignored){}}String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);int shown=0;List<MediaEntry> activeItems=section.equals("MTV")?mtvItems:all;
        for(MediaEntry c:activeItems){if(!matchSection(c))continue;
            if(PROFILE_CHILD.equals(activeProfile) && isAdultEntry(c)) continue;
            String itemName=c.name==null?"":c.name;String itemGroup=c.group==null?"":c.group;
            if(!q.isEmpty()&&!itemName.toLowerCase(Locale.ROOT).contains(q)&&!itemGroup.toLowerCase(Locale.ROOT).contains(q))continue;if(!filterMatches(activeFilter,itemGroup,itemName))continue;
            LinearLayout item=row();item.setId(View.generateViewId());item.setPadding(dp(13),dp(4),dp(12),dp(4));item.setBackground(bg(PANEL,12));item.setFocusable(true);item.setClickable(true);
            item.setBackground(bg(0xFF17233A,12));
            item.setOnFocusChangeListener((view,hasFocus)->view.setBackground(bg(hasFocus?ACCENT:0xFF17233A,12)));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62));ip.bottomMargin=dp(8);list.addView(item,ip);if(chips!=null){for(int ci=0;ci<chips.getChildCount();ci++)chips.getChildAt(ci).setNextFocusDownId(item.getId());item.setNextFocusUpId(chips.getChildAt(0).getId());}
            ImageView artwork=new ImageView(this);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setBackground(bg(CARD,8));item.addView(artwork,new LinearLayout.LayoutParams(dp(76),dp(52)));if(c.logo!=null&&!c.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(c.logo).placeholder(bg(CARD,8)).error(bg(CARD,8)).into(artwork);else artwork.setImageDrawable(bg(CARD,8));
            LinearLayout meta=column();meta.setPadding(dp(12),0,0,0);item.addView(meta,new LinearLayout.LayoutParams(0,-1,1));meta.addView(text(itemName,14,Color.WHITE,true));String detail=itemGroup+"  •  "+(c.type.equals("live")||c.type.equals("m3u")?"En direct":c.type.equals("series")?"Série":"VOD");if(!c.tvgId.isEmpty()&&epgNow.containsKey(c.tvgId))detail+="  •  "+epgNow.get(c.tvgId);if(section.equals("HISTORIQUE")){long pos=historyPosition(c);detail+="  •  Reprendre à "+(pos/60000)+" min";}meta.addView(text(detail,10,MUTED,false));
            TextView fav=text(favorites.contains(c.url)?"♥":"♡",22,0xFFFF7199,true);fav.setGravity(Gravity.CENTER);fav.setId(View.generateViewId());fav.setFocusable(true);fav.setClickable(true);fav.setBackground(bg(CARD,8));fav.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?ACCENT:CARD,8)));item.setNextFocusRightId(fav.getId());item.addView(fav,new LinearLayout.LayoutParams(dp(52),-1));fav.setNextFocusLeftId(item.getId());fav.setOnClickListener(v->{if(!favorites.add(c.url))favorites.remove(c.url);getPreferences(0).edit().putStringSet("favorites",new HashSet<>(favorites)).apply();fav.setText(favorites.contains(c.url)?"♥":"♡");});item.setOnClickListener(v->{if(c.type.equals("series"))showSeries(c);else play(c);});shown++;if(shown>=maxRenderItems)break;}
        if(shown>=maxRenderItems){
            TextView more=text("Afficher les chaînes / contenus suivants…",13,Color.WHITE,true);
            more.setGravity(Gravity.CENTER);more.setFocusable(true);more.setClickable(true);more.setPadding(dp(12),dp(10),dp(12),dp(10));more.setBackground(bg(ACCENT,10));
            LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(-1,dp(48));moreParams.topMargin=dp(4);moreParams.bottomMargin=dp(12);list.addView(more,moreParams);
            more.setOnClickListener(v->{maxRenderItems+=40;renderList();if(list.getChildCount()>1){View last=list.getChildAt(list.getChildCount()-2);last.requestFocus();}});
        }
        if(section.equals("ACCUEIL")&&(q.isEmpty()||q.trim().isEmpty())){renderHome();return;}if(shown==0){TextView empty=text(section.equals("MTV")?(mtvPlaylistUrl.isEmpty()?"Configure une playlist M3U autorisée pour MTV dans Paramètres.":"Aucune chaîne MTV disponible dans cette source."):(all.isEmpty()?"Aucun contenu chargé. Configure une source M3U ou Xtream dans Paramètres.":"Aucun résultat dans cette rubrique. Vérifie la source ou le filtre."),14,MUTED,false);empty.setPadding(dp(12),dp(30),dp(12),dp(20));list.addView(empty);}}
    private void play(MediaEntry c){failedInCurrentRun.clear();startPlayer(c);}
    private List<MediaEntry> playableQueue(MediaEntry selected){
        List<MediaEntry> source=section.equals("MTV")?mtvItems:all;
        ArrayList<MediaEntry> queue=new ArrayList<>();
        String query=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);
        for(MediaEntry entry:source){
            if(entry==null||entry.url==null||!(entry.url.startsWith("http://")||entry.url.startsWith("https://")))continue;
            if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(entry))continue;
            if(!matchSectionFor(entry))continue;
            String searchable=((entry.name==null?"":entry.name)+" "+(entry.group==null?"":entry.group)).toLowerCase(Locale.ROOT);
            if(!query.isEmpty()&&!searchable.contains(query))continue;
            if(!activeFilter.equals("Tout")&&!searchable.contains(activeFilter.toLowerCase(Locale.ROOT)))continue;
            queue.add(entry);
        }
        int selectedIndex=queue.indexOf(selected);if(selectedIndex<0){queue.add(0,selected);selectedIndex=0;}
        // Limite la file Media3 : une playlist M3U peut contenir des milliers de flux.
        final int maxQueue=80;if(queue.size()>maxQueue){int from=Math.max(0,Math.min(selectedIndex-maxQueue/2,queue.size()-maxQueue));queue=new ArrayList<>(queue.subList(from,from+maxQueue));}
        return queue;
    }
    private MediaEntry nextPlayable(MediaEntry failed){
        List<MediaEntry> queue=playableQueue(failed);int index=queue.indexOf(failed);
        for(int i=index+1;i<queue.size();i++)if(!failedInCurrentRun.contains(queue.get(i).url))return queue.get(i);
        return null;
    }
    private boolean matchSectionFor(MediaEntry entry){
        if(entry==null)return false;
        if(section.equals("MTV"))return mtvItems.contains(entry);
        if(section.equals("MUSIQUE"))return isMusicEntry(entry);
        if(section.equals("INFOS"))return isNewsEntry(entry);
        String group=entry.group==null?"":entry.group.toLowerCase(Locale.ROOT);
        if(section.equals("FAVORIS"))return favorites.contains(entry.url);
        if(section.equals("HISTORIQUE"))return hasHistory(entry);
        if(section.equals("TV EN DIRECT"))return entry.type.equals("live")||entry.type.equals("m3u");
        if(section.equals("FILMS"))return entry.type.equals("movie")||group.contains("movie")||group.contains("film")||group.contains("vod");
        if(section.equals("SÉRIES"))return entry.type.equals("series")||group.contains("series");
        if(section.equals("ANIMÉS"))return isAnimeEntry(entry);
        return true;
    }
    private boolean isAnimeEntry(MediaEntry e){
        String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);
        return v.contains("anime")||v.contains("animation")||v.contains("cartoon");
    }
    private boolean isMusicEntry(MediaEntry e){String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);String[] keys={"music","musique","radio","mtv","trace","mezzo","mcm","vevo","concert","hits","clip"};for(String k:keys)if(v.contains(k))return true;return false;}
    private boolean isNewsEntry(MediaEntry e){String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);String[] keys={"news","info","information","actualité","actualite","journal","france 24","bfm","cnews","lci","euronews","al jazeera","bbc","cnn"};for(String k:keys)if(v.contains(k))return true;return false;}
    private void startPlayer(MediaEntry c){
        if(c==null||c.url==null||!(c.url.startsWith("https://")||c.url.startsWith("http://"))){
            Toast.makeText(this,"Adresse du flux invalide",Toast.LENGTH_LONG).show();return;
        }
        try{
            savePlaybackPosition();releasePlayer();currentEntry=c;
            player=new ExoPlayer.Builder(getApplicationContext()).build();
            playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);playerView.setBackgroundColor(Color.BLACK);
            playerToolbar=row();playerToolbar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            TextView expand=text("⛶  Plein écran",13,Color.WHITE,true);
            expand.setFocusable(true);expand.setClickable(true);expand.setPadding(dp(14),dp(7),dp(14),dp(7));expand.setBackground(bg(CARD,10));
            expand.setOnFocusChangeListener((v,focus)->stylePlayerButton((TextView)v,focus));
            expand.setOnClickListener(v->togglePlayerExpanded());
            playerToolbar.addView(playerControl("⏮", "Précédent", ()->{if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();}),new LinearLayout.LayoutParams(dp(48),dp(38)));playerToolbar.addView(playerControl(player!=null&&player.isPlaying()?"Ⅱ":"▶","Pause / Lecture",()->{if(player!=null){if(player.isPlaying())player.pause();else player.play();}}),new LinearLayout.LayoutParams(dp(48),dp(38)));playerToolbar.addView(playerControl("⏭","Suivant",()->{if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();}),new LinearLayout.LayoutParams(dp(48),dp(38)));playerToolbar.addView(expand,new LinearLayout.LayoutParams(-2,dp(38)));
            content.addView(playerToolbar,2,new LinearLayout.LayoutParams(-1,dp(44)));
            if(catalogScroll.getParent()==playerArea)playerArea.removeView(catalogScroll);
            playerArea.addView(playerView,new LinearLayout.LayoutParams(0,dp(245),0.62f));
            playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(0,-1,0.38f));
            final ExoPlayer activePlayer=player;
            lastPlayedUrl=c.url;pendingResumePosition=historyPosition(c);
            historyKeysCache.add("history_"+key(c.url));prefs.edit().putString("last_url",c.url).putString("history_"+key(c.url),c.name==null?"":c.name).apply();
            List<MediaEntry> queue=playableQueue(c);ArrayList<MediaItem> mediaItems=new ArrayList<>();int startIndex=0;
            for(int i=0;i<queue.size();i++){
                MediaEntry entry=queue.get(i);
                mediaItems.add(new MediaItem.Builder().setUri(Uri.parse(entry.url)).setMediaId(entry.url).build());
                if(entry.url.equals(c.url))startIndex=i;
            }
            activePlayer.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_READY&&pendingResumePosition>5000&&player==activePlayer){
                        activePlayer.seekTo(pendingResumePosition);pendingResumePosition=0L;
                    }
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    if(currentEntry!=null)failedInCurrentRun.add(currentEntry.url);
                    postUi(()->{
                        if(player!=activePlayer)return;
                        if(activePlayer.hasNextMediaItem()){
                            Toast.makeText(MainActivity.this,"Flux indisponible. Passage au suivant…",Toast.LENGTH_SHORT).show();
                            activePlayer.seekToNextMediaItem();activePlayer.prepare();activePlayer.play();
                        }else{
                            status.setText("Lecture impossible : "+(currentEntry==null?c.name:currentEntry.name));
                            Toast.makeText(MainActivity.this,"Aucun autre flux disponible dans cette liste.",Toast.LENGTH_LONG).show();
                        }
                    });
                }
                @Override public void onMediaItemTransition(MediaItem item,int reason){
                    String uri=item.localConfiguration==null?"":item.localConfiguration.uri.toString();
                    for(MediaEntry entry:queue)if(entry.url.equals(uri)){
                        currentEntry=entry;lastPlayedUrl=entry.url;
                        historyKeysCache.add("history_"+key(entry.url));prefs.edit().putString("last_url",entry.url).putString("history_"+key(entry.url),entry.name==null?"":entry.name).apply();
                        break;
                    }
                }
            });
            activePlayer.setMediaItems(mediaItems,startIndex,pendingResumePosition);activePlayer.prepare();activePlayer.play();
            status.setText("Lecture : "+c.name);
        }catch(Exception e){releasePlayer();Toast.makeText(this,"Impossible de lancer ce flux",Toast.LENGTH_LONG).show();}
    }

    private void togglePlayerExpanded(){
        if(playerView==null||playerArea==null)return;
        playerFullscreen=!playerFullscreen;playerExpanded=playerFullscreen;
        // Masquer le conteneur complet de la barre laterale (pas seulement ses libelles).
        sidebarScroll.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        sidebar.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        content.setPadding(playerFullscreen?0:dp(25),playerFullscreen?0:dp(18),playerFullscreen?0:dp(25),playerFullscreen?0:dp(14));
        root.setBackgroundColor(playerFullscreen?Color.BLACK:BG);
        for(int i=0;i<content.getChildCount();i++){
            View child=content.getChildAt(i);
            if(child==playerArea)continue;
            child.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        }
        if(playerToolbar!=null)playerToolbar.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        if(catalogScroll!=null)catalogScroll.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        playerArea.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams areaParams=(LinearLayout.LayoutParams)playerArea.getLayoutParams();
        areaParams.width=-1;areaParams.height=0;areaParams.weight=1f;playerArea.setLayoutParams(areaParams);
        LinearLayout.LayoutParams videoParams=(LinearLayout.LayoutParams)playerView.getLayoutParams();
        videoParams.width=playerFullscreen?-1:0;videoParams.height=playerFullscreen?-1:dp(245);videoParams.weight=playerFullscreen?0f:0.62f;playerView.setLayoutParams(videoParams);
        if(!playerFullscreen&&catalogScroll.getParent()!=playerArea){playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(0,-1,0.38f));}
        applyImmersiveMode();playerView.requestFocus();
    }
    private void applyImmersiveMode(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSeries(MediaEntry s){if(!sourceMode.equals("XTREAM")){Toast.makeText(this,"Les épisodes doivent être fournis par une source VOD compatible.",Toast.LENGTH_LONG).show();return;}status.setText("Chargement des saisons…");executeIo(()->{String err=null;ArrayList<MediaEntry> eps=new ArrayList<>();try{JSONObject data=new JSONObject(get(api("get_series_info","&series_id="+enc(s.id))));JSONObject seasons=data.optJSONObject("episodes");if(seasons!=null){Iterator<String> keys=seasons.keys();while(keys.hasNext()){String k=keys.next();JSONArray a=seasons.optJSONArray(k);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");if(id.isEmpty())continue;String ext=o.optString("container_extension","mp4");String u=xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"series/"+enc(xtreamUser)+"/"+enc(xtreamPass)+"/"+id+"."+ext;eps.add(new MediaEntry(o.optString("title","Épisode "+(i+1)),u,"Saison "+k,"","episode"));}}}}catch(Exception e){err=e.getMessage();}final String error=err;postUi(()->{if(error!=null||eps.isEmpty()){Toast.makeText(this,"Aucun épisode disponible",Toast.LENGTH_LONG).show();return;}String[] names=new String[eps.size()];for(int i=0;i<eps.size();i++)names[i]=eps.get(i).group+" — "+eps.get(i).name;new AlertDialog.Builder(this).setTitle(s.name).setItems(names,(d,w)->play(eps.get(w))).setNegativeButton("Fermer",null).show();});});}

    private boolean isAdultEntry(MediaEntry item){
        if(item==null)return false;
        String value=((item.name==null?"":item.name)+" "+(item.group==null?"":item.group)).toLowerCase(Locale.ROOT);
        String[] blocked={"adult","xxx","18+","porn","erotic","adulte"};
        for(String word:blocked) if(value.contains(word)) return true;
        return false;
    }
    private void showProfileDialog(){
        String[] profiles={"Adulte","Enfant"};
        int selected=PROFILE_CHILD.equals(activeProfile)?1:0;
        new AlertDialog.Builder(this).setTitle("Profil GLOBAL TV")
            .setSingleChoiceItems(profiles,selected,(dialog,which)->{
                String next=profiles[which];
                if(PROFILE_CHILD.equals(next)){
                    activeProfile=next;
                    prefs.edit().putString("active_profile",next).apply();
                    dialog.dismiss(); renderList();
                    Toast.makeText(this,"Profil Enfant activé • Filtrage indicatif",Toast.LENGTH_LONG).show();
                }else if(PROFILE_CHILD.equals(activeProfile)){
                    String pinHash=prefs.getString("parental_pin_hash","");
                    if(pinHash.isEmpty()){
                        Toast.makeText(this,"Définis d'abord un code PIN parental",Toast.LENGTH_LONG).show();
                    }else{
                        EditText pin=field("Code PIN parental"); pin.setInputType(2);
                        new AlertDialog.Builder(this).setTitle("Accès profil Adulte").setView(pin)
                            .setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{
                                if(pinHash.equals(hash(pin.getText().toString()))){
                                    activeProfile=PROFILE_ADULT;
                                    prefs.edit().putString("active_profile",activeProfile).apply();
                                    renderList();
                                }else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
                            }).show();
                    }
                }else{
                    activeProfile=PROFILE_ADULT;
                    prefs.edit().putString("active_profile",activeProfile).apply();
                    renderList();
                }
                dialog.dismiss();
            }).setNegativeButton("Fermer",null).show();
    }
    private boolean isWebSection(String target){return "MTV".equals(target)||"WITV".equals(target)||"FRSTV".equals(target)||"FSLIVE".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target)||"BRAFLIX".equals(target);}
    private String webUrlFor(String target){if("WITV".equals(target))return "https://wiflix.mov/";if("FRSTV".equals(target))return "https://french-stream.net/";if("FSLIVE".equals(target))return "https://fstv.one/";if("EPI XTV".equals(target))return "https://www.xnxx.com/";if("DUDETV".equals(target))return "https://theporndude.com/";if("BRAFLIX".equals(target))return "https://braflix.uk/";return "https://oxax.tv/";}
    private void showMtvWebView(){showSiteWebView("https://oxax.tv/");}
    private void showSiteWebView(String url){
        activeWebUrl=url;
        if(mtvWebView==null){
            mtvWebView=new WebView(this);mtvWebView.setBackgroundColor(Color.BLACK);
            mtvWebView.setFocusable(true);mtvWebView.setFocusableInTouchMode(true);
            mtvWebView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mtvWebView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            android.webkit.WebSettings settings=mtvWebView.getSettings();
            settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);settings.setLoadsImagesAutomatically(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);settings.setSupportMultipleWindows(false);
            settings.setLoadWithOverviewMode(true);settings.setUseWideViewPort(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            mtvWebView.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){return false;}
                @Override public void onPageFinished(WebView view,String loaded){super.onPageFinished(view,loaded);if(isWebSection(section)&&view.getVisibility()==View.VISIBLE){view.requestFocus();}}
            });
            mtvWebView.setWebChromeClient(new WebChromeClient(){
                @Override public void onShowCustomView(View view,CustomViewCallback callback){showWebCustomView(view,callback);}
                @Override public void onHideCustomView(){hideWebCustomView();}
                @Override public void onShowCustomView(View view,int requestedOrientation,CustomViewCallback callback){showWebCustomView(view,callback);}
            });
            mtvWebView.setOnFocusChangeListener((v,focused)->{if(focused)v.setBackgroundColor(Color.BLACK);});
        }
        chipScroll.setVisibility(View.GONE);status.setVisibility(View.GONE);catalogScroll.setVisibility(View.GONE);if(playerArea!=null)playerArea.setVisibility(View.GONE);
        if(webContainer==null){
            webContainer=column();webContainer.setBackgroundColor(Color.BLACK);
            LinearLayout webNav=row();webNav.setPadding(dp(8),dp(4),dp(8),dp(4));
            TextView backMenu=text("☰  MENU PRINCIPAL",13,Color.WHITE,true);backMenu.setId(View.generateViewId());backMenu.setFocusable(true);backMenu.setFocusableInTouchMode(true);backMenu.setClickable(true);backMenu.setPadding(dp(16),dp(8),dp(16),dp(8));backMenu.setBackground(bg(ACCENT,10));
            backMenu.setOnClickListener(v->navigateTo("ACCUEIL"));webNav.addView(backMenu,new LinearLayout.LayoutParams(-2,dp(42)));
            webContainer.addView(webNav,new LinearLayout.LayoutParams(-1,dp(50)));
            webContainer.addView(mtvWebView,new LinearLayout.LayoutParams(-1,0,1));
            mtvWebView.setNextFocusUpId(backMenu.getId());
        }
        if(webContainer.getParent()==null)content.addView(webContainer,Math.min(3,content.getChildCount()),new LinearLayout.LayoutParams(-1,0,1));
        webContainer.setVisibility(View.VISIBLE);mtvWebView.setVisibility(View.VISIBLE);mtvWebView.setFocusable(true);mtvWebView.setFocusableInTouchMode(true);
        mtvWebView.bringToFront();mtvWebView.requestFocus();
        if(mtvWebView.getUrl()==null||!mtvWebView.getUrl().equals(url))mtvWebView.loadUrl(url);
    }
    private void showWebCustomView(View view,WebChromeClient.CustomViewCallback callback){
        if(webCustomView!=null){hideWebCustomView();return;}
        webCustomView=view;webCustomViewCallback=callback;
        webOriginalSystemUiVisibility=getWindow().getDecorView().getSystemUiVisibility();
        webFullscreenContainer=new FrameLayout(this);webFullscreenContainer.setBackgroundColor(Color.BLACK);
        webFullscreenContainer.addView(view,new FrameLayout.LayoutParams(-1,-1));
        View decor=getWindow().getDecorView();
        if(decor instanceof ViewGroup)((ViewGroup)decor).addView(webFullscreenContainer,new ViewGroup.LayoutParams(-1,-1));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void hideWebCustomView(){
        if(webCustomView==null)return;
        if(webFullscreenContainer!=null){View decor=getWindow().getDecorView();if(decor instanceof ViewGroup)((ViewGroup)decor).removeView(webFullscreenContainer);webFullscreenContainer.removeAllViews();webFullscreenContainer=null;}
        webCustomView=null;
        if(webCustomViewCallback!=null){webCustomViewCallback.onCustomViewHidden();webCustomViewCallback=null;}
        getWindow().getDecorView().setSystemUiVisibility(webOriginalSystemUiVisibility);
        applyImmersiveMode();
        if(mtvWebView!=null)mtvWebView.requestFocus();
    }
    private void hideMtvWebView(){
        activeWebUrl="";
        if(mtvWebView!=null){mtvWebView.setVisibility(View.GONE);}if(webContainer!=null){webContainer.setVisibility(View.GONE);if(webContainer.getParent()==content)content.removeView(webContainer);}
        chipScroll.setVisibility(View.VISIBLE);status.setVisibility(View.VISIBLE);catalogScroll.setVisibility(View.VISIBLE);if(playerArea!=null)playerArea.setVisibility(View.VISIBLE);
    }
    private void showMtvPlaylistDialog(){
        EditText url=field("URL de playlist M3U autorisée pour MTV");
        url.setText(mtvPlaylistUrl);
        new AlertDialog.Builder(this).setTitle("Source MTV")
            .setMessage("Ajoute uniquement une playlist ou un flux que tu es autorisé à utiliser. GLOBAL TV ne récupère pas automatiquement le contenu du site oxax.tv.")
            .setView(url).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer et charger",(d,w)->{
                String value=url.getText().toString().trim();
                if(!(value.startsWith("https://")||value.startsWith("http://"))){
                    Toast.makeText(this,"URL HTTP(S) requise",Toast.LENGTH_LONG).show();return;
                }
                mtvPlaylistUrl=value;
                prefs.edit().putString("mtv_playlist_url",value).apply();
                loadMtvPlaylist(value);
            }).show();
    }
    private void loadMtvPlaylist(String url){
        status.setText("Chargement de la source MTV…");
        executeIo(()->{
            ArrayList<MediaEntry> parsed=new ArrayList<>();String error=null;
            try{
                String data=get(url);BufferedReader reader=new BufferedReader(new StringReader(data));
                String line,name=null,group="MTV",logo="",tvgId="";int count=0;
                while((line=reader.readLine())!=null&&count<MAX_CATALOG_ITEMS){
                    line=line.trim();
                    if(line.startsWith("#EXTINF:")){
                        name=line.substring(line.lastIndexOf(',')+1).trim();group="MTV";logo="";tvgId="";
                        int g=line.indexOf("group-title=\"");
                        if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}
                        int tid=line.indexOf("tvg-id=\"");
                        if(tid>=0){int e=line.indexOf('"',tid+8);if(e>tid)tvgId=line.substring(tid+8,e);}
                        int l=line.indexOf("tvg-logo=\"");
                        if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}
                    }else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){
                        if(line.startsWith("https://")||line.startsWith("http://")){
                            MediaEntry entry=new MediaEntry(name,line,group,logo,"m3u");entry.tvgId=tvgId;parsed.add(entry);count++;
                        }name=null;
                    }
                }
            }catch(Exception ex){error=ex.getMessage();}
            final String err=error;
            postUi(()->{
                if(err!=null){status.setText("Erreur source MTV : "+err);return;}
                mtvItems.clear();mtvItems.addAll(parsed);
                status.setText(parsed.size()+" éléments chargés • Source MTV dédiée");
                if(section.equals("MTV"))renderList();
            });
        });
    }
    private void showSettings(){String[] options={"Changer de profil (Adulte / Enfant)","Ajouter une playlist M3U","Gérer les playlists M3U","Connexion Xtream Codes","Configurer le guide TV (XMLTV)","Définir / modifier le code parental","Effacer l’historique et les reprises","Configurer la source MTV"};new AlertDialog.Builder(this).setTitle("GLOBAL TV • Paramètres").setItems(options,(d,w)->{if(w==0)showProfileDialog();else if(w==1)showM3uDialog();else if(w==2)showPlaylistManager();else if(w==3)showXtreamDialog();else if(w==4)showEpgDialog();else if(w==5)showPinDialog();else if(w==6)new AlertDialog.Builder(this).setTitle("Effacer l’historique ?").setMessage("Les positions de lecture et l’historique seront supprimés.").setNegativeButton("Annuler",null).setPositiveButton("Effacer",(a,b)->{Map<String,?> allPrefs=prefs.getAll();SharedPreferences.Editor ed=prefs.edit();for(String k:allPrefs.keySet())if(k.startsWith("history_")||k.startsWith("position_"))ed.remove(k);ed.apply();renderList();}).show();else if(w==7)showMtvPlaylistDialog();}).show();}
    private boolean isAdultWebSection(String target){return "MTV".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target);}
    private boolean isProtectedSection(String target){return target.equals("FILMS")||target.equals("SÉRIES")||target.equals("ANIMÉS")||isAdultWebSection(target);}
    private void requestParentalAccess(String target){String expected=prefs.getString("parental_pin_hash","");EditText pin=field("Code PIN parental");pin.setInputType(2);new AlertDialog.Builder(this).setTitle("Contrôle parental").setMessage("Entre le code PIN pour accéder à cette rubrique.").setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{if(expected.equals(hash(pin.getText().toString())))navigateTo(target);else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();}).show();}
    private void showPinDialog(){EditText pin=field("Nouveau code PIN (4 à 8 chiffres)");pin.setInputType(2);new AlertDialog.Builder(this).setTitle("Contrôle parental").setMessage("Laisse vide pour désactiver le contrôle parental.").setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{String v=pin.getText().toString();if(v.isEmpty())prefs.edit().remove("parental_pin_hash").apply();else if(v.matches("\\d{4,8}"))prefs.edit().putString("parental_pin_hash",hash(v)).apply();else Toast.makeText(this,"Utilise 4 à 8 chiffres",Toast.LENGTH_LONG).show();}).show();}
    private String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.US,"%02x",x));return out.toString();}catch(Exception e){return value;}}
    private String key(String value){return hash(value).substring(0,24);}
    private void savePlaybackPosition(){if(player!=null&&!lastPlayedUrl.isEmpty()){long pos=player.getCurrentPosition();if(pos>0)prefs.edit().putLong("position_"+key(lastPlayedUrl),pos).apply();}}
    private void showEpgDialog(){EditText input=field("URL du fichier XMLTV (HTTPS conseillé)");input.setText(epgUrl);new AlertDialog.Builder(this).setTitle("Guide TV XMLTV").setMessage("Ajoute l’URL XMLTV de ton fournisseur ou une source autorisée. Les chaînes doivent avoir un tvg-id correspondant.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){epgUrl=u;prefs.edit().putString("epg_url",u).apply();loadEpg(u);}else Toast.makeText(this,"URL HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void loadEpg(String url){status.setText("Chargement du guide TV…");executeIo(()->{Map<String,String> now=new HashMap<>();String err=null;try{HttpURLConnection c=open(url);try{if(c.getResponseCode()<200||c.getResponseCode()>=300)throw new IOException("HTTP "+c.getResponseCode());XmlPullParser x=XmlPullParserFactory.newInstance().newPullParser();x.setInput(c.getInputStream(),"UTF-8");String channel=null,title=null;long start=0,stop=0,nowMs=System.currentTimeMillis();int event=x.getEventType();while(event!=XmlPullParser.END_DOCUMENT){if(event==XmlPullParser.START_TAG&&"programme".equals(x.getName())){channel=x.getAttributeValue(null,"channel");start=parseXmlTvTime(x.getAttributeValue(null,"start"));stop=parseXmlTvTime(x.getAttributeValue(null,"stop"));title=null;}else if(event==XmlPullParser.START_TAG&&"title".equals(x.getName())&&channel!=null){title=x.nextText();if(start<=nowMs&&stop>nowMs&&!title.isEmpty())now.put(channel,title);}event=x.next();}}finally{c.disconnect();}}catch(Exception e){err=e.getMessage();}final String error=err;epgNow.clear();epgNow.putAll(now);postUi(()->{status.setText(error==null?now.size()+" programmes en cours • Guide TV XMLTV":"Erreur EPG : "+error);renderList();});});}
    private long parseXmlTvTime(String value){if(value==null)return 0;try{String v=value.trim();SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);if(v.length()==14)v+=" +0000";else if(v.length()>14&&v.charAt(14)!=' ')v=v.substring(0,14)+" "+v.substring(14);Date d=f.parse(v);return d==null?0:d.getTime();}catch(Exception e){return 0;}}
    private void showM3uDialog(){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("https://exemple.com/playlist.m3u");input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);input.setText("");new AlertDialog.Builder(this).setTitle("Ajouter une playlist M3U").setMessage("Ajoute une nouvelle source. Les chaînes des playlists seront réunies dans le même catalogue.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Ajouter et charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){section="TV EN DIRECT";refreshAllNavStyles();loadPlaylist(u);}else Toast.makeText(this,"Adresse HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void showPlaylistManager(){ArrayList<String> urls=new ArrayList<>(playlistSources);if(urls.isEmpty()){new AlertDialog.Builder(this).setTitle("Sources M3U").setMessage("Aucune source enregistrée.").setPositiveButton("Ajouter", (d,w)->showM3uDialog()).show();return;}String[] labels=new String[urls.size()];for(int i=0;i<urls.size();i++)labels[i]=(i+1)+". "+Uri.parse(urls.get(i)).getHost()+" — "+urls.get(i);new AlertDialog.Builder(this).setTitle("Sources M3U • "+urls.size()).setItems(labels,(d,which)->new AlertDialog.Builder(this).setTitle("Gérer cette source").setMessage(urls.get(which)).setNegativeButton("Fermer",null).setNeutralButton("Supprimer",(a,b)->{playlistSources.remove(urls.get(which));prefs.edit().putStringSet("m3u_sources",new HashSet<>(playlistSources)).apply();loadAllPlaylists();}).setPositiveButton("Recharger",(a,b)->loadPlaylist(urls.get(which))).show()).setPositiveButton("Ajouter une source",(d,w)->showM3uDialog()).setNegativeButton("Fermer",null).show();}
    private void showXtreamDialog(){LinearLayout form=column();form.setPadding(dp(8),dp(4),dp(8),dp(4));EditText host=field("URL du serveur (https://serveur:port)");EditText user=field("Nom d’utilisateur");EditText pass=field("Mot de passe");pass.setInputType(129);form.addView(host);form.addView(user);form.addView(pass);new AlertDialog.Builder(this).setTitle("Connexion Xtream Codes").setMessage("Utilise les identifiants fournis par ton fournisseur IPTV autorisé. HTTPS est recommandé.").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Se connecter",(d,w)->{String h=host.getText().toString().trim(),u=user.getText().toString().trim(),p=pass.getText().toString();try{Uri uri=Uri.parse(h);if((!"https".equalsIgnoreCase(uri.getScheme())&&!("http".equalsIgnoreCase(uri.getScheme())))||uri.getHost()==null||u.isEmpty()||p.isEmpty())throw new Exception();xtreamBase=h;xtreamUser=u;xtreamPass=p;section="ACCUEIL";heading.setText("Bienvenue sur GLOBAL TV");loadXtream();}catch(Exception e){Toast.makeText(this,"Vérifie l’URL et les identifiants",Toast.LENGTH_LONG).show();}}).show();}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(10),dp(6),dp(10),dp(6));return e;}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(webCustomView!=null){hideWebCustomView();return true;}
            if(playerView!=null){if(playerFullscreen){togglePlayerExpanded();return true;}savePlaybackPosition();releasePlayer();status.setText("Lecture fermée");return true;}
            if(isWebSection(section)){navigateTo("ACCUEIL");return true;}
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&isWebSection(section)&&webContainer!=null&&webContainer.getVisibility()==View.VISIBLE&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&mtvWebView.hasFocus()){
            View menu=null;View webNav=webContainer.getChildAt(0);if(webNav instanceof ViewGroup)menu=((ViewGroup)webNav).getChildAt(0);if(menu!=null){menu.requestFocus();return true;}
        }
        if(isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_CENTER||event.getKeyCode()==KeyEvent.KEYCODE_ENTER)&&!mtvWebView.hasFocus())mtvWebView.requestFocus();
        }return super.dispatchKeyEvent(event);}
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(level>=TRIM_MEMORY_RUNNING_LOW){
            try{com.bumptech.glide.Glide.get(this).clearMemory();}catch(Exception ignored){}
        }
        if(level>=TRIM_MEMORY_BACKGROUND){
            try{com.bumptech.glide.Glide.get(this).trimMemory(level);}catch(Exception ignored){}
        }
    }
    @Override public void onLowMemory(){
        super.onLowMemory();
        try{com.bumptech.glide.Glide.get(this).clearMemory();}catch(Exception ignored){}
    }
    @Override protected void onStop(){super.onStop();if(player!=null){savePlaybackPosition();player.pause();}}
    @Override protected void onDestroy(){
        destroyed=true;if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
        savePlaybackPosition();releasePlayer();io.shutdownNow();super.onDestroy();
    }
}

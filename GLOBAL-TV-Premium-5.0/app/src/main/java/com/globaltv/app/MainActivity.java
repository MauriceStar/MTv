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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

/** GLOBAL TV Premium - Android TV / Google TV. */
public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_PLAYLIST="https://iptv-org.github.io/iptv/index.m3u";
    private static final int BG=0xFF080D19, PANEL=0xFF111A2B, CARD=0xFF17233A, MUTED=0xFF91A0BA, ACCENT=0xFF746BFF, CYAN=0xFF27C8C0;
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
    private String lastPlayedUrl="";
    private long pendingResumePosition=0L;
    private LinearLayout root,sidebar,content,list,chips,playerToolbar,playerArea,webContainer;
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
        favorites.addAll(prefs.getStringSet("favorites",new HashSet<>()));epgUrl=prefs.getString("epg_url","");mtvPlaylistUrl=prefs.getString("mtv_playlist_url","");lastPlayedUrl=prefs.getString("last_url","");buildUi();loadPlaylist(DEFAULT_PLAYLIST);if(!epgUrl.isEmpty())loadEpg(epgUrl);}
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
        TextView brand=text("GLOBAL TV",25,Color.WHITE,true);sidebar.addView(brand,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView premium=text("P R E M I U M",10,CYAN,true);sidebar.addView(premium,new LinearLayout.LayoutParams(-1,dp(25)));
        TextView tagline=text("LE DIVERTISSEMENT\nSANS FRONTIÈRES",10,MUTED,true);sidebar.addView(tagline,new LinearLayout.LayoutParams(-1,dp(54)));
        addNav("⌂   Accueil","ACCUEIL");addNav("▣   TV en direct","TV EN DIRECT");addNav("♫   Musique","MUSIQUE");addNav("▤   Infos","INFOS");addNav("▶   Films","FILMS");addNav("▤   Séries","SÉRIES");addNav("✦   Animés","ANIMÉS");addNav("♡   Mes favoris","FAVORIS");addNav("🌐   AOXTv","MTV");addNav("◷   Historique","HISTORIQUE");addNav("▦   Guide TV","GUIDE TV");addNav("🌐   WiTv","WITV");addNav("🌐   FrSTv","FRSTV");addNav("🌐   FsLive","FSLIVE");addNav("🌐   EpiXtv","EPI XTV");addNav("🌐   DudeTV","DUDETV");addNav("🌐   Braflix","BRAFLIX");
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
    private void addNav(String label,String target){TextView v=text(label,14,0xFFD9E1F2,true);v.setPadding(dp(13),0,dp(8),0);v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0x00172035,10));
        v.setOnFocusChangeListener((view,hasFocus)->{
            view.setBackground(bg(hasFocus?ACCENT:(section.equals(target)?0xFF253452:0x00172035),10));
            ((TextView)view).setTextColor(hasFocus?Color.WHITE:0xFFD9E1F2);
        });LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.bottomMargin=dp(3);sidebar.addView(v,p);v.setOnClickListener(w->{if(isAdultWebSection(target)&&PROFILE_CHILD.equals(activeProfile)){Toast.makeText(this,"Cette rubrique est réservée au profil Adulte.",Toast.LENGTH_LONG).show();return;}if(isProtectedSection(target)&&!prefs.getString("parental_pin_hash","").isEmpty()){requestParentalAccess(target);return;}navigateTo(target);});}
    private void navigateTo(String target){
        if(playerFullscreen)togglePlayerExpanded();
        section=target;activeFilter="Tout";search.setHint("⌕  Rechercher…");updateChips();
        if(isWebSection(target)){showSiteWebView(webUrlFor(target));return;}
        hideMtvWebView();renderList();
        if(target.equals("PARAMÈTRES"))showSettings();
        else if(search!=null&&!search.hasFocus())search.requestFocus();
    }
    private void updateChips(){if(chips==null)return;chips.removeAllViews();String[] opts;if(section.equals("TV EN DIRECT"))opts=new String[]{"Tout","France","Afrique","Sports","News","Music","Kids"};else if(section.equals("FAVORIS")||section.equals("HISTORIQUE")||section.equals("GUIDE TV")||section.equals("MUSIQUE")||section.equals("INFOS"))opts=new String[]{"Tout"};else opts=new String[]{"Tout","Action","Comédie","Drame","Aventure","Fantasy","Science-fiction","Animation"};int previousId=View.NO_ID;for(String value:opts){final String filter=value;TextView c=text(value,12,activeFilter.equals(value)?Color.WHITE:0xFFB8C4D8,true);c.setGravity(Gravity.CENTER);c.setPadding(dp(15),0,dp(15),0);c.setBackground(bg(activeFilter.equals(value)?ACCENT:CARD,18));c.setFocusable(true);c.setFocusableInTouchMode(true);c.setClickable(true);c.setId(View.generateViewId());c.setNextFocusUpId(search.getId());if(previousId!=View.NO_ID)c.setNextFocusLeftId(previousId);if(previousId!=View.NO_ID)chips.getChildAt(chips.getChildCount()-1).setNextFocusRightId(c.getId());previousId=c.getId();c.setOnFocusChangeListener((v,focus)->{if(focus){Rect rect=new Rect();v.getDrawingRect(rect);chipScroll.requestChildRectangleOnScreen(v,rect,true);}});c.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){v.performClick();return true;}if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_DPAD_LEFT){int idx=chips.indexOfChild(v),next=idx+(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);if(next>=0&&next<chips.getChildCount()){chips.getChildAt(next).requestFocus();return true;}}if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN){if(list.getChildCount()>0){list.getChildAt(0).requestFocus();return true;}}return false;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.rightMargin=dp(8);chips.addView(c,p);c.setOnClickListener(v->{activeFilter=filter;updateChips();renderList();});}search.setNextFocusDownId(chips.getChildAt(0).getId());}


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
    private void loadPlaylist(String url){final int request=++catalogRequestId;status.setText("Connexion à la playlist M3U…");executeIo(()->{ArrayList<MediaEntry> parsed=new ArrayList<>();String err=null;try{String data=get(url);BufferedReader r=new BufferedReader(new StringReader(data));String line,name=null,group="Autres",logo="",tvgId="";int count=0;while((line=r.readLine())!=null&&count<MAX_CATALOG_ITEMS){line=line.trim();if(line.startsWith("#EXTINF:")){name=line.substring(line.lastIndexOf(',')+1).trim();group="Autres";logo="";tvgId="";int g=line.indexOf("group-title=\"");if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}int tid=line.indexOf("tvg-id=\"");if(tid>=0){int te=line.indexOf('"',tid+8);if(te>tid)tvgId=line.substring(tid+8,te);}int l=line.indexOf("tvg-logo=\"");if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}}else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){if(line.startsWith("https://")||line.startsWith("http://")){MediaEntry entry=new MediaEntry(name,line,group,logo,"m3u");entry.tvgId=tvgId;parsed.add(entry);count++;}name=null;tvgId="";}}}catch(Exception e){err=e.getMessage();}final String error=err;postUi(()->{if(request!=catalogRequestId)return;if(error!=null){status.setText("Erreur M3U : "+error);return;}all.clear();all.addAll(parsed);sourceMode="M3U";sourceLabel.setText("SOURCE : M3U");status.setText(parsed.size()+" éléments chargés • Playlist M3U");renderList();});});}

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
    private void renderList(){if(list==null)return;list.removeAllViews();if(section.equals("HISTORIQUE")){historyKeysCache.clear();try{for(String prefKey:prefs.getAll().keySet())if(prefKey.startsWith("history_"))historyKeysCache.add(prefKey);}catch(Exception ignored){}}String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);int shown=0;List<MediaEntry> activeItems=section.equals("MTV")?mtvItems:all;
        for(MediaEntry c:activeItems){if(!matchSection(c))continue;
            if(PROFILE_CHILD.equals(activeProfile) && isAdultEntry(c)) continue;
            String itemName=c.name==null?"":c.name;String itemGroup=c.group==null?"":c.group;
            if(!q.isEmpty()&&!itemName.toLowerCase(Locale.ROOT).contains(q)&&!itemGroup.toLowerCase(Locale.ROOT).contains(q))continue;if(!activeFilter.equals("Tout")&&!itemGroup.toLowerCase(Locale.ROOT).contains(activeFilter.toLowerCase(Locale.ROOT))&&!itemName.toLowerCase(Locale.ROOT).contains(activeFilter.toLowerCase(Locale.ROOT)))continue;
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
        if(shown==0){TextView empty=text(section.equals("MTV")?(mtvPlaylistUrl.isEmpty()?"Configure une playlist M3U autorisée pour MTV dans Paramètres.":"Aucune chaîne MTV disponible dans cette source."):(all.isEmpty()?"Aucun contenu chargé. Configure une source M3U ou Xtream dans Paramètres.":"Aucun résultat dans cette rubrique. Vérifie la source ou le filtre."),14,MUTED,false);empty.setPadding(dp(12),dp(30),dp(12),dp(20));list.addView(empty);}}
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
            playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);playerView.setBackgroundColor(Color.BLACK);
            playerToolbar=row();playerToolbar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            TextView expand=text("⛶  Plein écran",13,Color.WHITE,true);
            expand.setFocusable(true);expand.setClickable(true);expand.setPadding(dp(14),dp(7),dp(14),dp(7));expand.setBackground(bg(CARD,10));
            expand.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?ACCENT:CARD,10)));
            expand.setOnClickListener(v->togglePlayerExpanded());
            playerToolbar.addView(expand,new LinearLayout.LayoutParams(-2,dp(38)));
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
        sidebar.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        for(int i=0;i<content.getChildCount();i++){
            View child=content.getChildAt(i);
            if(child==playerArea)continue;
            child.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        }
        if(playerToolbar!=null)playerToolbar.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        if(catalogScroll!=null)catalogScroll.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
        LinearLayout.LayoutParams areaParams=(LinearLayout.LayoutParams)playerArea.getLayoutParams();
        areaParams.width=-1;areaParams.height=0;areaParams.weight=1f;playerArea.setLayoutParams(areaParams);
        LinearLayout.LayoutParams videoParams=(LinearLayout.LayoutParams)playerView.getLayoutParams();
        videoParams.width=-1;videoParams.height=playerFullscreen?-1:dp(245);videoParams.weight=playerFullscreen?1f:0f;playerView.setLayoutParams(videoParams);
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
            mtvWebView.setWebChromeClient(new WebChromeClient());
            mtvWebView.setOnFocusChangeListener((v,focused)->{if(focused)v.setBackgroundColor(Color.BLACK);});
        }
        chipScroll.setVisibility(View.GONE);status.setVisibility(View.GONE);catalogScroll.setVisibility(View.GONE);
        if(webContainer==null){
            webContainer=column();webContainer.setBackgroundColor(Color.BLACK);
            LinearLayout webNav=row();webNav.setPadding(dp(8),dp(4),dp(8),dp(4));
            TextView backMenu=text("☰  MENU PRINCIPAL",13,Color.WHITE,true);backMenu.setFocusable(true);backMenu.setFocusableInTouchMode(true);backMenu.setClickable(true);backMenu.setPadding(dp(16),dp(8),dp(16),dp(8));backMenu.setBackground(bg(ACCENT,10));
            backMenu.setOnClickListener(v->navigateTo("ACCUEIL"));webNav.addView(backMenu,new LinearLayout.LayoutParams(-2,dp(42)));
            webContainer.addView(webNav,new LinearLayout.LayoutParams(-1,dp(50)));
            webContainer.addView(mtvWebView,new LinearLayout.LayoutParams(-1,0,1));
            mtvWebView.setNextFocusUpId(backMenu.getId());backMenu.setId(View.generateViewId());
        }
        if(webContainer.getParent()==null)content.addView(webContainer,Math.min(3,content.getChildCount()),new LinearLayout.LayoutParams(-1,0,1));
        webContainer.setVisibility(View.VISIBLE);mtvWebView.setVisibility(View.VISIBLE);mtvWebView.setFocusable(true);mtvWebView.setFocusableInTouchMode(true);
        mtvWebView.bringToFront();mtvWebView.requestFocus();
        if(mtvWebView.getUrl()==null||!mtvWebView.getUrl().equals(url))mtvWebView.loadUrl(url);
    }
    private void hideMtvWebView(){
        activeWebUrl="";
        if(mtvWebView!=null){mtvWebView.setVisibility(View.GONE);}if(webContainer!=null){webContainer.setVisibility(View.GONE);if(webContainer.getParent()==content)content.removeView(webContainer);}
        chipScroll.setVisibility(View.VISIBLE);status.setVisibility(View.VISIBLE);catalogScroll.setVisibility(View.VISIBLE);
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
    private void showSettings(){String[] options={"Changer de profil (Adulte / Enfant)","Charger une playlist M3U","Connexion Xtream Codes","Configurer le guide TV (XMLTV)","Définir / modifier le code parental","Effacer l’historique et les reprises","Configurer la source MTV"};new AlertDialog.Builder(this).setTitle("GLOBAL TV • Paramètres").setItems(options,(d,w)->{if(w==0)showProfileDialog();else if(w==1)showM3uDialog();else if(w==2)showXtreamDialog();else if(w==3)showEpgDialog();else if(w==4)showPinDialog();else if(w==5)new AlertDialog.Builder(this).setTitle("Effacer l’historique ?").setMessage("Les positions de lecture et l’historique seront supprimés.").setNegativeButton("Annuler",null).setPositiveButton("Effacer",(a,b)->{Map<String,?> allPrefs=prefs.getAll();SharedPreferences.Editor ed=prefs.edit();for(String k:allPrefs.keySet())if(k.startsWith("history_")||k.startsWith("position_"))ed.remove(k);ed.apply();renderList();}).show();else if(w==6)showMtvPlaylistDialog();}).show();}
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
    private void showM3uDialog(){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("https://exemple.com/playlist.m3u");input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);input.setText(DEFAULT_PLAYLIST);new AlertDialog.Builder(this).setTitle("Playlist M3U").setMessage("Entre l’adresse d’une playlist dont tu possèdes les droits d’utilisation.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){section="TV EN DIRECT";heading.setText(section);loadPlaylist(u);}else Toast.makeText(this,"Adresse HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void showXtreamDialog(){LinearLayout form=column();form.setPadding(dp(8),dp(4),dp(8),dp(4));EditText host=field("URL du serveur (https://serveur:port)");EditText user=field("Nom d’utilisateur");EditText pass=field("Mot de passe");pass.setInputType(129);form.addView(host);form.addView(user);form.addView(pass);new AlertDialog.Builder(this).setTitle("Connexion Xtream Codes").setMessage("Utilise les identifiants fournis par ton fournisseur IPTV autorisé. HTTPS est recommandé.").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Se connecter",(d,w)->{String h=host.getText().toString().trim(),u=user.getText().toString().trim(),p=pass.getText().toString();try{Uri uri=Uri.parse(h);if((!"https".equalsIgnoreCase(uri.getScheme())&&!("http".equalsIgnoreCase(uri.getScheme())))||uri.getHost()==null||u.isEmpty()||p.isEmpty())throw new Exception();xtreamBase=h;xtreamUser=u;xtreamPass=p;section="ACCUEIL";heading.setText("Bienvenue sur GLOBAL TV");loadXtream();}catch(Exception e){Toast.makeText(this,"Vérifie l’URL et les identifiants",Toast.LENGTH_LONG).show();}}).show();}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(10),dp(6),dp(10),dp(6));return e;}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(playerView!=null){if(playerFullscreen){togglePlayerExpanded();return true;}savePlaybackPosition();releasePlayer();status.setText("Lecture fermée");return true;}
            if(isWebSection(section)){navigateTo("ACCUEIL");return true;}
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&isWebSection(section)&&webContainer!=null&&webContainer.getVisibility()==View.VISIBLE&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&mtvWebView.hasFocus()){
            View menu=webContainer.getChildAt(0).getChildAt(0);if(menu!=null){menu.requestFocus();return true;}
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

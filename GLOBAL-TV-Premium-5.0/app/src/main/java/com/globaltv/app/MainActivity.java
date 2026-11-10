package com.globaltv.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
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
    private final Map<String,String> epgNow=new ConcurrentHashMap<>();
    private SharedPreferences prefs;
    private String epgUrl="";
    private String lastPlayedUrl="";
    private long pendingResumePosition=0L;
    private LinearLayout root,sidebar,content,list,chips;
    private ScrollView catalogScroll;
    private boolean playerExpanded=false;
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
        sidebar=column();sidebar.setPadding(dp(18),dp(22),dp(12),dp(14));sidebar.setBackgroundColor(0xFF0D1525);root.addView(sidebar,new LinearLayout.LayoutParams(dp(220),-1));
        TextView brand=text("GLOBAL TV",25,Color.WHITE,true);sidebar.addView(brand,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView premium=text("P R E M I U M",10,CYAN,true);sidebar.addView(premium,new LinearLayout.LayoutParams(-1,dp(25)));
        TextView tagline=text("LE DIVERTISSEMENT\nSANS FRONTIÈRES",10,MUTED,true);sidebar.addView(tagline,new LinearLayout.LayoutParams(-1,dp(54)));
        addNav("⌂   Accueil","ACCUEIL");addNav("▣   TV en direct","TV EN DIRECT");addNav("♫   MTV","MTV");addNav("▶   Films","FILMS");addNav("▤   Séries","SÉRIES");addNav("✦   Animés","ANIMÉS");addNav("♡   Mes favoris","FAVORIS");addNav("◷   Historique","HISTORIQUE");addNav("▦   Guide TV","GUIDE TV");
        View spacer=new View(this);sidebar.addView(spacer,new LinearLayout.LayoutParams(1,0,1));
        sourceLabel=text("SOURCE : M3U",10,CYAN,true);sidebar.addView(sourceLabel,new LinearLayout.LayoutParams(-1,dp(28)));
        addNav("⚙   Sources & paramètres","PARAMÈTRES");
        // Point d'entrée D-pad : le focus ne reste pas bloqué dans la barre latérale.
        View firstContentTarget=search;
        for(int i=0;i<sidebar.getChildCount();i++){
            View v=sidebar.getChildAt(i);
            if(v.isFocusable()){
                v.setNextFocusRightId(firstContentTarget.getId());
            }
        }
        search.setNextFocusLeftId(sidebar.getChildAt(4).getId());
        content=column();content.setPadding(dp(25),dp(18),dp(25),dp(14));root.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout top=row();content.addView(top,new LinearLayout.LayoutParams(-1,dp(58)));
        heading=text("Bienvenue sur GLOBAL TV",26,Color.WHITE,true);top.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        search=new EditText(this);search.setId(View.generateViewId());search.setSingleLine(true);search.setTextSize(14);search.setHintTextColor(MUTED);search.setTextColor(Color.WHITE);search.setHint("⌕  Rechercher un contenu…");search.setPadding(dp(14),0,dp(14),0);search.setBackground(bg(CARD,12));top.addView(search,new LinearLayout.LayoutParams(dp(300),dp(44)));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
                pendingSearch=()->{if(!destroyed)renderList();};
                mainHandler.postDelayed(pendingSearch,180);
            }
            public void afterTextChanged(Editable e){}
        });
        HorizontalScrollView chipScroll=new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(true);
        chipScroll.setScrollbarFadingEnabled(false);
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
        content.addView(catalogScroll,new LinearLayout.LayoutParams(-1,0,1));
        list=column();list.setPadding(0,dp(4),0,dp(12));catalogScroll.addView(list);
        TextView foot=text("GLOBAL TV • Les flux et catalogues dépendent des droits, de la disponibilité et des régions.",10,0xFF65738D,false);content.addView(foot,new LinearLayout.LayoutParams(-1,dp(26)));
        updateChips();
    }
    private void addNav(String label,String target){TextView v=text(label,14,0xFFD9E1F2,true);v.setPadding(dp(13),0,dp(8),0);v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0x00172035,10));
        v.setOnFocusChangeListener((view,hasFocus)->{
            view.setBackground(bg(hasFocus?ACCENT:(section.equals(target)?0xFF253452:0x00172035),10));
            ((TextView)view).setTextColor(hasFocus?Color.WHITE:0xFFD9E1F2);
        });LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.bottomMargin=dp(3);sidebar.addView(v,p);v.setOnClickListener(w->{if(isProtectedSection(target)&&!prefs.getString("parental_pin_hash","").isEmpty()){requestParentalAccess(target);return;}navigateTo(target);});}
    private void navigateTo(String target){section=target;activeFilter="Tout";heading.setText(target.equals("ACCUEIL")?"Bienvenue sur GLOBAL TV":target.equals("MTV")?"MTV":"".equals(target)?target:target);search.setHint("⌕  Rechercher…");updateChips();renderList();
        if(target.equals("PARAMÈTRES"))showSettings();
        else if(search!=null && !search.hasFocus()) search.requestFocus();
    }
    private void updateChips(){if(chips==null)return;chips.removeAllViews();String[] opts;if(section.equals("TV EN DIRECT")||section.equals("MTV"))opts=new String[]{"Tout","France","Afrique","Sports","News","Music","Kids"};else if(section.equals("FAVORIS")||section.equals("HISTORIQUE")||section.equals("GUIDE TV"))opts=new String[]{"Tout"};else opts=new String[]{"Tout","Action","Comédie","Drame","Aventure","Fantasy","Science-fiction","Animation"};for(String s:opts){TextView c=text(s,12,activeFilter.equals(s)?Color.WHITE:0xFFB8C4D8,true);c.setGravity(Gravity.CENTER);c.setPadding(dp(15),0,dp(15),0);c.setBackground(bg(activeFilter.equals(s)?ACCENT:CARD,18));c.setFocusable(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(34));p.rightMargin=dp(8);chips.addView(c,p);c.setOnClickListener(v->{activeFilter=s;updateChips();renderList();});}}

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
        if(playerView!=null){playerView.setPlayer(null);if(content!=null)content.removeView(playerView);playerView=null;}
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

    private boolean matchSection(MediaEntry c){
        if(section.equals("MTV")) return mtvItems.contains(c);
        String g=c.group.toLowerCase(Locale.ROOT),n=c.name.toLowerCase(Locale.ROOT);if(section.equals("FAVORIS"))return favorites.contains(c.url);if(section.equals("HISTORIQUE"))return prefs.getString("history_"+key(c.url),"").length()>0;if(section.equals("GUIDE TV"))return (c.type.equals("live")||c.type.equals("m3u"))&&!c.tvgId.isEmpty();if(section.equals("TV EN DIRECT"))return c.type.equals("live")||c.type.equals("m3u");if(section.equals("FILMS"))return c.type.equals("movie")||g.contains("movie")||g.contains("film")||g.contains("vod");if(section.equals("SÉRIES"))return c.type.equals("series")||g.contains("series");if(section.equals("ANIMÉS"))return g.contains("anime")||g.contains("animation")||g.contains("cartoon")||n.contains("anime");return true;}
    private void renderList(){if(list==null)return;list.removeAllViews();String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);int shown=0;List<MediaEntry> activeItems=section.equals("MTV")?mtvItems:all;
        for(MediaEntry c:activeItems){if(!matchSection(c))continue;
            if(PROFILE_CHILD.equals(activeProfile) && isAdultEntry(c)) continue;
            if(!q.isEmpty()&&!c.name.toLowerCase(Locale.ROOT).contains(q)&&!c.group.toLowerCase(Locale.ROOT).contains(q))continue;if(!activeFilter.equals("Tout")&&!c.group.toLowerCase(Locale.ROOT).contains(activeFilter.toLowerCase(Locale.ROOT))&&!c.name.toLowerCase(Locale.ROOT).contains(activeFilter.toLowerCase(Locale.ROOT)))continue;
            LinearLayout item=row();item.setPadding(dp(13),dp(4),dp(12),dp(4));item.setBackground(bg(PANEL,12));item.setFocusable(true);item.setClickable(true);
            item.setBackground(bg(0xFF17233A,12));
            item.setOnFocusChangeListener((view,hasFocus)->view.setBackground(bg(hasFocus?ACCENT:0xFF17233A,12)));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62));ip.bottomMargin=dp(8);list.addView(item,ip);
            ImageView artwork=new ImageView(this);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setBackground(bg(CARD,8));item.addView(artwork,new LinearLayout.LayoutParams(dp(76),dp(52)));if(c.logo!=null&&!c.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(c.logo).placeholder(bg(CARD,8)).error(bg(CARD,8)).into(artwork);else artwork.setImageDrawable(bg(CARD,8));
            LinearLayout meta=column();meta.setPadding(dp(12),0,0,0);item.addView(meta,new LinearLayout.LayoutParams(0,-1,1));meta.addView(text(c.name,14,Color.WHITE,true));String detail=c.group+"  •  "+(c.type.equals("live")||c.type.equals("m3u")?"En direct":c.type.equals("series")?"Série":"VOD");if(!c.tvgId.isEmpty()&&epgNow.containsKey(c.tvgId))detail+="  •  "+epgNow.get(c.tvgId);if(section.equals("HISTORIQUE")){long pos=prefs.getLong("position_"+key(c.url),0L);detail+="  •  Reprendre à "+(pos/60000)+" min";}meta.addView(text(detail,10,MUTED,false));
            TextView fav=text(favorites.contains(c.url)?"♥":"♡",22,0xFFFF7199,true);fav.setGravity(Gravity.CENTER);item.addView(fav,new LinearLayout.LayoutParams(dp(44),-1));fav.setOnClickListener(v->{if(!favorites.add(c.url))favorites.remove(c.url);getPreferences(0).edit().putStringSet("favorites",new HashSet<>(favorites)).apply();fav.setText(favorites.contains(c.url)?"♥":"♡");});item.setOnClickListener(v->{if(c.type.equals("series"))showSeries(c);else play(c);});shown++;if(shown>=maxRenderItems)break;}
        if(shown==0){TextView empty=text(section.equals("MTV")?(mtvPlaylistUrl.isEmpty()?"Configure une playlist M3U autorisée pour MTV dans Paramètres.":"Aucune chaîne MTV disponible dans cette source."):(all.isEmpty()?"Aucun contenu chargé. Configure une source M3U ou Xtream dans Paramètres.":"Aucun résultat dans cette rubrique. Vérifie la source ou le filtre."),14,MUTED,false);empty.setPadding(dp(12),dp(30),dp(12),dp(20));list.addView(empty);}}
    private void play(MediaEntry c){failedInCurrentRun.clear();startPlayer(c);}
    private MediaEntry nextPlayable(MediaEntry failed){
        List<MediaEntry> source=section.equals("MTV")?mtvItems:all;
        int index=source.indexOf(failed);
        for(int i=index+1;i<source.size();i++){
            MediaEntry candidate=source.get(i);
            if(candidate!=null && candidate.url!=null && !failedInCurrentRun.contains(candidate.url)
                && (candidate.url.startsWith("http://")||candidate.url.startsWith("https://"))
                && matchSectionFor(candidate)){
                return candidate;
            }
        }
        return null;
    }
    private boolean matchSectionFor(MediaEntry entry){
        if(section.equals("MTV")) return mtvItems.contains(entry);
        if(section.equals("TV EN DIRECT")) return entry.type.equals("live")||entry.type.equals("m3u");
        if(section.equals("FILMS")) return entry.type.equals("movie")||entry.group.toLowerCase(Locale.ROOT).contains("film")||entry.group.toLowerCase(Locale.ROOT).contains("vod");
        if(section.equals("SÉRIES")) return entry.type.equals("series")||entry.group.toLowerCase(Locale.ROOT).contains("series");
        if(section.equals("ANIMÉS")) return isAnimeEntry(entry);
        return true;
    }
    private boolean isAnimeEntry(MediaEntry e){
        String v=(e.name+" "+e.group).toLowerCase(Locale.ROOT);
        return v.contains("anime")||v.contains("animation")||v.contains("cartoon");
    }
    private void startPlayer(MediaEntry c){
        if(c==null||c.url==null||!(c.url.startsWith("https://")||c.url.startsWith("http://"))){
            Toast.makeText(this,"Adresse du flux invalide",Toast.LENGTH_LONG).show();return;
        }
        try{
            savePlaybackPosition();releasePlayer();
            currentEntry=c;
            failedInCurrentRun.remove(c.url);
            player=new ExoPlayer.Builder(getApplicationContext()).build();
            playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);
            playerView.setBackgroundColor(Color.BLACK);
            LinearLayout playerToolbar=row();
            playerToolbar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            TextView expand=text("⛶  Plein écran",13,Color.WHITE,true);
            expand.setFocusable(true);expand.setClickable(true);expand.setPadding(dp(14),dp(7),dp(14),dp(7));
            expand.setBackground(bg(CARD,10));
            expand.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?ACCENT:CARD,10)));
            expand.setOnClickListener(v->togglePlayerExpanded());
            playerToolbar.addView(expand,new LinearLayout.LayoutParams(-2,dp(38)));
            content.addView(playerToolbar,2,new LinearLayout.LayoutParams(-1,dp(44)));
            content.addView(playerView,3,new LinearLayout.LayoutParams(-1,dp(285)));
            final ExoPlayer activePlayer=player;
            lastPlayedUrl=c.url;pendingResumePosition=prefs.getLong("position_"+key(c.url),0L);
            prefs.edit().putString("last_url",c.url).putString("history_"+key(c.url),c.name).apply();
            activePlayer.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_READY&&pendingResumePosition>5000&&player==activePlayer){
                        activePlayer.seekTo(pendingResumePosition);pendingResumePosition=0L;
                    }
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    failedInCurrentRun.add(c.url);
                    MediaEntry next=nextPlayable(c);
                    postUi(()->{
                        if(player!=activePlayer)return;
                        if(next!=null){
                            Toast.makeText(MainActivity.this,"Flux indisponible. Essai de la chaîne suivante…",Toast.LENGTH_SHORT).show();
                            startPlayer(next);
                        }else{
                            status.setText("Lecture impossible : "+c.name);
                            Toast.makeText(MainActivity.this,"Aucun flux suivant disponible dans cette rubrique.",Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
            activePlayer.setMediaItem(MediaItem.fromUri(Uri.parse(c.url)));activePlayer.prepare();activePlayer.play();
            status.setText("Lecture : "+c.name);
        }catch(Exception e){releasePlayer();Toast.makeText(this,"Impossible de lancer ce flux",Toast.LENGTH_LONG).show();}
    }
    private void togglePlayerExpanded(){
        if(playerView==null)return;
        playerExpanded=!playerExpanded;
        if(playerExpanded){
            sidebar.setVisibility(View.GONE);
            for(int i=0;i<content.getChildCount();i++){
                View child=content.getChildAt(i);
                if(child!=playerView && child.getVisibility()!=View.GONE) child.setVisibility(View.GONE);
            }
            playerView.setLayoutParams(new LinearLayout.LayoutParams(-1,-1));
            playerView.requestFocus();
        }else{
            sidebar.setVisibility(View.VISIBLE);
            for(int i=0;i<content.getChildCount();i++) content.getChildAt(i).setVisibility(View.VISIBLE);
            playerView.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(285)));
        }
    }

    private void showSeries(MediaEntry s){if(!sourceMode.equals("XTREAM")){Toast.makeText(this,"Les épisodes doivent être fournis par une source VOD compatible.",Toast.LENGTH_LONG).show();return;}status.setText("Chargement des saisons…");executeIo(()->{String err=null;ArrayList<MediaEntry> eps=new ArrayList<>();try{JSONObject data=new JSONObject(get(api("get_series_info","&series_id="+enc(s.id))));JSONObject seasons=data.optJSONObject("episodes");if(seasons!=null){Iterator<String> keys=seasons.keys();while(keys.hasNext()){String k=keys.next();JSONArray a=seasons.optJSONArray(k);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");if(id.isEmpty())continue;String ext=o.optString("container_extension","mp4");String u=xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"series/"+enc(xtreamUser)+"/"+enc(xtreamPass)+"/"+id+"."+ext;eps.add(new MediaEntry(o.optString("title","Épisode "+(i+1)),u,"Saison "+k,"","episode"));}}}}catch(Exception e){err=e.getMessage();}final String error=err;postUi(()->{if(error!=null||eps.isEmpty()){Toast.makeText(this,"Aucun épisode disponible",Toast.LENGTH_LONG).show();return;}String[] names=new String[eps.size()];for(int i=0;i<eps.size();i++)names[i]=eps.get(i).group+" — "+eps.get(i).name;new AlertDialog.Builder(this).setTitle(s.name).setItems(names,(d,w)->play(eps.get(w))).setNegativeButton("Fermer",null).show();});});}

    private boolean isAdultEntry(MediaEntry item){
        String value=(item.name+" "+item.group).toLowerCase(Locale.ROOT);
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
    private boolean isProtectedSection(String target){return target.equals("FILMS")||target.equals("SÉRIES")||target.equals("ANIMÉS");}
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
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK&&playerView!=null){
            if(playerExpanded){togglePlayerExpanded();return true;}
            savePlaybackPosition();releasePlayer();playerExpanded=false;status.setText("Lecture fermée");return true;
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

package com.globaltv.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.animation.ValueAnimator;
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
import java.text.Normalizer;
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
    private static final String[] BUILT_IN_PLAYLISTS={
        "https://iptv-org.github.io/iptv/countries/fr.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/fr.m3u",
        "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
        "https://raw.githubusercontent.com/reklamalinir/M3U-1/master/m3u%20playlists/3-%20Africa%20African%20Iptv%20Channels.m3u"
    };
    private static final int BG=0xFF080D19, PANEL=0xFF111A2B, CARD=0xFF17233A, MUTED=0xFF91A0BA, ACCENT=0xFF746BFF, CYAN=0xFF27C8C0, FOCUS_GOLD=0xFFFFB52E, FOCUS_RED=0xFFFF654F;
    // Pool borne pour eviter une file de taches sans limite sur les box peu puissantes.
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(12),
        r->{Thread t=new Thread(r,"globaltv-io");t.setPriority(Thread.NORM_PRIORITY-1);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    // Cache les termes normalisés : ils ne doivent pas être recalculés pour chaque élément du catalogue.
    private String cachedSearchQuery=null;
    private String[] cachedSearchTokens=new String[0];
    // Index léger des champs statiques : évite de normaliser les mêmes noms à chaque recherche.
    private final Map<String,String> normalizedSearchIndex=new HashMap<>();
    // Les vignettes de la page d’accueil sont calculées une seule fois par catalogue.
    private ArrayList<MediaEntry> homeFeaturedCache=null;
    private volatile boolean destroyed=false;
    private volatile int catalogRequestId=0;
    private volatile int mtvPlaylistRequestId=0;
    private volatile int epgRequestId=0;
    private String parentalAuthorizedTarget="";
    private boolean parentalSettingsAccessGranted=false;
    private boolean resumePlaybackOnStart=false;
    private BrandSplash brandSplash;
    private long splashStartedAt=0L;
    private boolean splashLoadFinished=false;
    private Runnable splashTimeoutRunnable;
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
    private final LinkedHashSet<String> recentlyPlayable=new LinkedHashSet<>();
    private final Map<String,String> epgNow=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Set<String>> favoriteGroups=new LinkedHashMap<>();
    private final Map<String,String> epgNext=new ConcurrentHashMap<>();
    private final Map<String,Long> epgNowStart=new ConcurrentHashMap<>();
    private final Map<String,Long> epgNowStop=new ConcurrentHashMap<>();
    private final Map<String,Long> epgNextStart=new ConcurrentHashMap<>();
    private boolean showOnlyEpgChannels=false;
    private SharedPreferences prefs;
    private String epgUrl="";
    private final LinkedHashSet<String> playlistSources=new LinkedHashSet<>();
    private String lastPlayedUrl="";
    private long pendingResumePosition=0L;
    private Runnable playbackSaveRunnable;
    private LinearLayout root,sidebar,content,list,chips,playerToolbar,playerArea,webContainer,sidebarShell;
    private final Map<String,TextView> navViews=new LinkedHashMap<>();
    private View webCustomView;
    private WebChromeClient.CustomViewCallback webCustomViewCallback;
    private FrameLayout webFullscreenContainer;
    private int webOriginalSystemUiVisibility=0;
    private ScrollView sidebarScroll,catalogScroll;
    private HorizontalScrollView chipScroll;
    private WebView mtvWebView;
    private final Set<WebView> webPopupViews=new HashSet<>();
    private String activeWebUrl="";
    private boolean playerExpanded=false;
    private boolean playerFullscreen=false;
    private View playerFullscreenTop,playerFullscreenChips,playerFullscreenStatus,playerFullscreenFooter;
    private MediaEntry currentEntry;
    private final Set<String> failedInCurrentRun=new HashSet<>();
    // Reprises réseau bornées par flux pour éviter les boucles de préparation.
    private final Map<String,Integer> transientRetryAttempts=new HashMap<>();
    private Runnable pendingPlaybackRetry;
    private EditText search;
    private TextView status,heading,sourceLabel;
    private PlayerView playerView;
    private ExoPlayer player;
    private int playerResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
    private String section="ACCUEIL",activeFilter="Tout",sourceMode="M3U",xtreamBase="",xtreamUser="",xtreamPass="";

    static class MediaEntry {
        String name,url,group,logo,type,id,container,tvgId;
        String plot="",rating="",year="",duration="",genre="",cast="",director="";
        int seasonNumber=-1,episodeNumber=-1;
        String seriesTitle="";
        MediaEntry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;id="";container="m3u8";tvgId="";}
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);prefs=getPreferences(0);AutoPilotScheduler.sync(this);
        activeProfile=prefs.getString("active_profile",PROFILE_ADULT);
        configureMemoryBudget();
        loadProfileFavoriteData();Set<String> priorPlayable=prefs.getStringSet("recently_playable",new LinkedHashSet<>());if(priorPlayable!=null)recentlyPlayable.addAll(priorPlayable);while(recentlyPlayable.size()>250)recentlyPlayable.remove(recentlyPlayable.iterator().next());Set<String> savedPlaylistSources=prefs.getStringSet("m3u_sources",new LinkedHashSet<>());
        playlistSources.add(DEFAULT_PLAYLIST);
        playlistSources.addAll(Arrays.asList(BUILT_IN_PLAYLISTS));
        if(savedPlaylistSources!=null)for(String savedSource:savedPlaylistSources)if(savedSource!=null&&!savedSource.trim().isEmpty())playlistSources.add(savedSource);
        prefs.edit().putStringSet("m3u_sources",new LinkedHashSet<>(playlistSources)).apply();
        epgUrl=prefs.getString("epg_url","");mtvPlaylistUrl=prefs.getString("mtv_playlist_url","");lastPlayedUrl=prefs.getString("last_url","");buildUi();showBrandSplash();loadAllPlaylists();if(!epgUrl.isEmpty())loadEpg(epgUrl);}
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

    // Splash MTv : le nom s'illumine dans le téléviseur, puis la parabole rejoint le logo.
    private void showBrandSplash(){
        ViewGroup decor=(ViewGroup)getWindow().getDecorView();
        splashLoadFinished=false;
        splashStartedAt=android.os.SystemClock.uptimeMillis();
        BrandSplash splash=new BrandSplash(this);
        brandSplash=splash;
        decor.addView(splash,new ViewGroup.LayoutParams(-1,-1));
        splash.start();
        splashTimeoutRunnable=()->{if(brandSplash==null||destroyed)return;if(!splashLoadFinished){catalogRequestId++;splashLoadFinished=true;if(status!=null)status.setText("Délai de connexion dépassé. Vérifie Internet ou tes sources.");renderList();brandSplash.setMessage("CONNEXION LENTE • VÉRIFIE TES SOURCES");brandSplash.setProgressComplete();}finishBrandSplash();};
        mainHandler.postDelayed(splashTimeoutRunnable,120000L);
    }
    private void onStartupPlaylistLoadFinished(boolean success,String message){if(brandSplash==null)return;splashLoadFinished=true;brandSplash.setMessage(message);brandSplash.setProgressComplete();long elapsed=android.os.SystemClock.uptimeMillis()-splashStartedAt;long remaining=Math.max(0L,15000L-elapsed);mainHandler.postDelayed(()->{if(splashLoadFinished)finishBrandSplash();},remaining);}
    private void finishBrandSplash(){if(brandSplash==null)return;if(splashTimeoutRunnable!=null)mainHandler.removeCallbacks(splashTimeoutRunnable);BrandSplash splash=brandSplash;brandSplash=null;splash.animate().alpha(0f).setDuration(500L).withEndAction(()->{if(splash.getParent() instanceof ViewGroup)((ViewGroup)splash.getParent()).removeView(splash);}).start();}
    private final class BrandSplash extends FrameLayout {
        private final TextView messageView;
        private final ProgressBar progressBar;
        private boolean completed = false;
        private final long startedAt = android.os.SystemClock.uptimeMillis();
        private final Runnable progressTicker = new Runnable() {
            @Override public void run() {
                if (completed || brandSplash != BrandSplash.this) return;
                long elapsed = android.os.SystemClock.uptimeMillis() - startedAt;
                progressBar.setProgress((int)Math.min(900L, elapsed * 900L / 15000L));
                postDelayed(this, 100L);
            }
        };

        void start() { post(progressTicker); }
        void setMessage(String value) {
            messageView.setText(value == null ? "CHARGEMENT DU CATALOGUE…" : value);
        }
        void setProgressComplete() {
            completed = true;
            removeCallbacks(progressTicker);
            progressBar.setProgress(1000, true);
        }

        BrandSplash(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            setFocusable(true);
            setClickable(true);

            // Single splash artwork: fill the entire display without a duplicate backdrop.
            ImageView artwork = new ImageView(context);
            artwork.setImageResource(R.drawable.splash_mtv);
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(artwork, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout loading = new LinearLayout(context);
            loading.setOrientation(LinearLayout.VERTICAL);
            loading.setGravity(Gravity.CENTER_HORIZONTAL);
            loading.setPadding(dp(22), dp(12), dp(22), dp(12));
            GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00101830, 0xD9000716, 0xF0000614}
            );
            panel.setCornerRadius(dp(14));
            loading.setBackground(panel);

            messageView = new TextView(context);
            messageView.setText("PRÉPARATION DES CHAÎNES TV…");
            messageView.setTextColor(Color.WHITE);
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            messageView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            messageView.setGravity(Gravity.CENTER);
            messageView.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
            loading.addView(messageView, new LinearLayout.LayoutParams(-1, -2));

            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(false);
            progressBar.setMax(1000);
            progressBar.setProgress(0);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC52B));
                progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
            }
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(9));
            barParams.topMargin = dp(12);
            loading.addView(progressBar, barParams);

            FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
            );
            loadingParams.leftMargin = dp(22);
            loadingParams.rightMargin = dp(22);
            loadingParams.bottomMargin = dp(18);
            addView(loading, loadingParams);
        }
    }

    private void buildUi(){
        root=row();root.setBackgroundColor(BG);setContentView(root);
        sidebarShell=column();sidebarShell.setBackgroundColor(0xFF0D1525);root.addView(sidebarShell,new LinearLayout.LayoutParams(dp(220),-1));
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER);brand.setPadding(dp(2),dp(2),dp(2),dp(2));GradientDrawable brandBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF101827,0xFF1A2436,0xFF101827});brandBg.setCornerRadius(dp(14));brand.setBackground(brandBg);android.widget.ImageView brandLogo=new android.widget.ImageView(this);// Keep the approved logo as a single bitmap asset; no custom drawing or text recoloring.
        brandLogo.setImageResource(R.drawable.mtv_brand_logo);brandLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);brandLogo.setAdjustViewBounds(true);brandLogo.setContentDescription("Logo MTv avec parabole et satellite");brand.addView(brandLogo,new LinearLayout.LayoutParams(-1,dp(78)));LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(-1,dp(86));brandParams.setMargins(dp(10),dp(10),dp(10),dp(9));sidebarShell.addView(brand,brandParams);
        sidebarScroll=new ScrollView(this);sidebarScroll.setFillViewport(true);sidebarScroll.setVerticalScrollBarEnabled(true);sidebarScroll.setScrollbarFadingEnabled(false);sidebarScroll.setSmoothScrollingEnabled(true);sidebarScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);sidebarShell.addView(sidebarScroll,new LinearLayout.LayoutParams(-1,0,1f));
        sidebar=column();sidebar.setPadding(dp(18),dp(8),dp(12),dp(14));sidebar.setBackgroundColor(0xFF0D1525);sidebarScroll.addView(sidebar,new ScrollView.LayoutParams(-1,-2));
        addNav("⌂   Accueil","ACCUEIL");addNav("▣   TV en direct","TV EN DIRECT");addNav("⚽   Sports","SPORTS");addNav("▶   Films","FILMS");addNav("🎬   Cinéma","CINÉMA");addNav("▤   Séries","SÉRIES");addNav("🎭   Divertissement","DIVERTISSEMENT");addNav("▤   Actualités","INFOS");addNav("♫   Musique","MUSIQUE");addNav("🎥   Documentaire","DOCUMENTAIRE");addNav("📰   Magazine","MAGAZINE");addNav("🧒   Jeunesse","JEUNESSE");addNav("💃   Novelas","NOVELAS");addNav("✦   Animés","ANIMÉS");addNav("🎓   Éducation","ÉDUCATION");addNav("🔬   Scientifique","SCIENCE & TECHNOLOGIE");addNav("✝   Religion","RELIGION");addNav("♡   Mes favoris","FAVORIS");addNav("◷   Historique","HISTORIQUE");addNav("▦   Guide TV","GUIDE TV");addNav("🌐   AOXTv","MTV");addNav("🌐   WiTv","WITV");addNav("🌐   FrSTv","FRSTV");addNav("🌐   FsLive","FSLIVE");addNav("🌐   EpiXtv","EPI XTV");addNav("🌐   DudeTV","DUDETV");addNav("🌐   Braflix","BRAFLIX");
        View spacer=new View(this);sidebar.addView(spacer,new LinearLayout.LayoutParams(1,dp(12)));
        sourceLabel=text("SOURCE : M3U",10,CYAN,true);sourceLabel.setVisibility(View.GONE);sidebar.addView(sourceLabel,new LinearLayout.LayoutParams(-1,dp(28)));
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
        search.setNextFocusLeftId(navViews.get("ACCUEIL").getId());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
                pendingSearch=()->{if(!destroyed){maxRenderItems=120;renderList();}};
                mainHandler.postDelayed(pendingSearch,300);
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
        status=text("",12,MUTED,false);status.setVisibility(View.GONE);content.addView(status,new LinearLayout.LayoutParams(-1,dp(0)));
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
        TextView foot=text("GLOBAL TV • Les flux et catalogues dépendent des droits, de la disponibilité et des régions.",10,0xFF65738D,false);foot.setId(View.generateViewId());foot.setVisibility(View.GONE);content.addView(foot,new LinearLayout.LayoutParams(-1,dp(26)));
        updateChips();
    }
    private void addNav(String label,String target){TextView v=text(label,14,0xFFD9E1F2,true);v.setPadding(dp(13),0,dp(8),0);v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0x00172035,10));navViews.put(target,v);
        v.setOnFocusChangeListener((view,hasFocus)->refreshNavStyle(target,hasFocus));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.bottomMargin=dp(3);sidebar.addView(v,p);v.setOnClickListener(w->navigateTo(target));refreshNavStyle(target,v.hasFocus());}
    private void refreshNavStyle(String target,boolean focused){TextView v=navViews.get(target);if(v==null)return;boolean selected=section.equals(target);if(selected){GradientDrawable d=bg(0xFF6E54FF,10);d.setStroke(dp(2),0xFF42E8F5);v.setBackground(d);v.setTextColor(Color.WHITE);}else if(focused){GradientDrawable d=bg(FOCUS_GOLD,10);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);v.setTextColor(0xFF17111A);}else{v.setBackground(bg(0x00172035,10));v.setTextColor(0xFFD9E1F2);}}
    private void refreshAllNavStyles(){for(String target:navViews.keySet())refreshNavStyle(target,navViews.get(target).hasFocus());}
    private void navigateTo(String target){
 if(PROFILE_CHILD.equals(activeProfile)&&isAdultWebSection(target)){Toast.makeText(this,"Cette rubrique est réservée au profil Adulte.",Toast.LENGTH_LONG).show();return;}
 String pinHash=prefs.getString("parental_pin_hash","");
 if(isProtectedSection(target)&&!pinHash.isEmpty()&&!target.equals(parentalAuthorizedTarget)){requestParentalAccess(target);return;}
 if(target.equals(parentalAuthorizedTarget))parentalAuthorizedTarget="";
 if(playerFullscreen)togglePlayerExpanded();
 section=target;refreshAllNavStyles();activeFilter="Tout";maxRenderItems=120;
 if(pendingSearch!=null){mainHandler.removeCallbacks(pendingSearch);pendingSearch=null;}
 search.setText("");search.setHint("⌕  Rechercher…");updateChips();TextView targetNav=navViews.get(target);if(targetNav!=null)search.setNextFocusLeftId(targetNav.getId());
 if(catalogScroll!=null)catalogScroll.scrollTo(0,0);
 if(isWebSection(target)){showSiteWebView(webUrlFor(target));return;}
        hideMtvWebView();
 renderList();
 if(target.equals("PARAMÈTRES"))showSettings();else{TextView selectedNav=navViews.get(target);if(selectedNav!=null)selectedNav.requestFocus();}
}
    private void updateChips(){if(chips==null)return;chips.removeAllViews();if(chipScroll!=null)chipScroll.setVisibility(section.equals("ACCUEIL")?View.GONE:View.VISIBLE);String[] opts;if(section.equals("FAVORIS")){ArrayList<String> values=new ArrayList<>();values.add("Tout");values.addAll(favoriteGroups.keySet());values.add("＋ Créer une liste");opts=values.toArray(new String[0]);}else opts=new String[]{"Tout","Afrique","Europe","Asie","Amérique du Nord","Amérique du Sud","Océanie","Togo","Bénin","Côte d’Ivoire","Ghana","Nigeria","Sénégal","Cameroun","Congo","RDC","Afrique du Sud","Maroc","Algérie","Tunisie","Égypte","Kenya","Ouganda","Tanzanie","Éthiopie","Rwanda","Zambie","France","Royaume-Uni","Allemagne","Espagne","Italie","Portugal","Belgique","Pays-Bas","Suisse","Autriche","Suède","Norvège","Danemark","Grèce","Pologne","Russie","USA","Canada","Mexique","Brésil","Argentine","Colombie","Chili","Pérou","Uruguay","Équateur","Inde","Chine","Japon","Corée du Sud","Pakistan","Bangladesh","Indonésie","Philippines","Thaïlande","Vietnam","Malaisie","Singapour","Turquie","Arabie saoudite","Émirats arabes unis","Australie","Nouvelle-Zélande","Fidji","Papouasie-Nouvelle-Guinée"};int previousId=View.NO_ID;for(String value:opts){final String filter=value;TextView c=text(value,12,activeFilter.equals(value)?Color.WHITE:0xFFB8C4D8,true);c.setGravity(Gravity.CENTER);c.setPadding(dp(15),0,dp(15),0);c.setBackground(bg(activeFilter.equals(value)?ACCENT:CARD,18));c.setFocusable(true);c.setFocusableInTouchMode(true);c.setClickable(true);c.setId(View.generateViewId());c.setNextFocusUpId(search.getId());if(previousId!=View.NO_ID)c.setNextFocusLeftId(previousId);if(previousId!=View.NO_ID)chips.getChildAt(chips.getChildCount()-1).setNextFocusRightId(c.getId());previousId=c.getId();c.setOnFocusChangeListener((v,focus)->{if(focus){if(filter.equals(activeFilter)){GradientDrawable d=bg(ACCENT,18);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);((TextView)v).setTextColor(Color.WHITE);}else{GradientDrawable d=bg(FOCUS_GOLD,18);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);((TextView)v).setTextColor(0xFF17111A);}Rect rect=new Rect();v.getDrawingRect(rect);chipScroll.requestChildRectangleOnScreen(v,rect,true);}else{v.setBackground(bg(filter.equals(activeFilter)?ACCENT:CARD,18));((TextView)v).setTextColor(filter.equals(activeFilter)?Color.WHITE:0xFFB8C4D8);}});c.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){v.performClick();return true;}if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_DPAD_LEFT){int idx=chips.indexOfChild(v),next=idx+(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);if(next>=0&&next<chips.getChildCount()){chips.getChildAt(next).requestFocus();return true;}}if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN){if(list.getChildCount()>0){list.getChildAt(0).requestFocus();return true;}}return false;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.rightMargin=dp(8);chips.addView(c,p);c.setOnClickListener(v->{if(section.equals("FAVORIS")&&filter.equals("＋ Créer une liste")){showCreateFavoriteGroup();return;}activeFilter=filter;maxRenderItems=120;updateChips();renderList();for(int i=0;i<chips.getChildCount();i++){View selected=chips.getChildAt(i);if(selected instanceof TextView&&filter.equals(((TextView)selected).getText().toString())){selected.requestFocus();break;}}});}search.setNextFocusDownId(chips.getChildAt(0).getId());}


    private void executeIo(Runnable task){
        if(destroyed)return;
        try{io.execute(task);}catch(RejectedExecutionException ex){
            if(!destroyed)postUi(()->Toast.makeText(this,"Trop d’operations en cours. Reessaie dans un instant.",Toast.LENGTH_LONG).show());
        }
    }
    private void postUi(Runnable task){
        if(task==null||destroyed)return;
        mainHandler.post(()->{
            if(destroyed||isFinishing())return;
            try{task.run();}
            catch(Exception uiError){android.util.Log.e("MTv-UI","UI task failed",uiError);}
        });
    }
    private void releasePlayer(){
        if(pendingPlaybackRetry!=null){mainHandler.removeCallbacks(pendingPlaybackRetry);pendingPlaybackRetry=null;}
        if(playbackSaveRunnable!=null){mainHandler.removeCallbacks(playbackSaveRunnable);playbackSaveRunnable=null;}
        transientRetryAttempts.clear();failedInCurrentRun.clear();currentEntry=null;
        ExoPlayer oldPlayer=player;
        PlayerView oldView=playerView;
        LinearLayout oldToolbar=playerToolbar;
        try{if(playerFullscreen&&oldView!=null)togglePlayerExpanded();}catch(Exception e){android.util.Log.w("MTv-Player","Unable to leave fullscreen cleanly",e);}
        player=null;playerView=null;playerToolbar=null;
        playerFullscreen=false;playerExpanded=false;
        try{if(sidebarShell!=null)sidebarShell.setVisibility(View.VISIBLE);if(sidebarScroll!=null)sidebarScroll.setVisibility(View.VISIBLE);if(sidebar!=null)sidebar.setVisibility(View.VISIBLE);if(content!=null)for(int i=0;i<content.getChildCount();i++)content.getChildAt(i).setVisibility(View.VISIBLE);}catch(Exception e){android.util.Log.w("MTv-Player","Unable to restore layout",e);}
        try{if(oldView!=null){oldView.setPlayer(null);if(oldView.getParent() instanceof ViewGroup)((ViewGroup)oldView.getParent()).removeView(oldView);}}catch(Exception e){android.util.Log.w("MTv-Player","Unable to detach player view",e);}
        try{if(playerArea!=null)playerArea.setOrientation(LinearLayout.HORIZONTAL);if(catalogScroll!=null&&playerArea!=null&&catalogScroll.getParent()!=playerArea)playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,-1));}catch(Exception e){android.util.Log.w("MTv-Player","Unable to restore catalogue",e);}
        try{if(oldToolbar!=null&&oldToolbar.getParent() instanceof ViewGroup)((ViewGroup)oldToolbar.getParent()).removeView(oldToolbar);if(playerArea!=null)for(int i=playerArea.getChildCount()-1;i>=0;i--){View child=playerArea.getChildAt(i);if(child instanceof LinearLayout&&child!=catalogScroll&&((LinearLayout)child).getChildCount()==0)playerArea.removeViewAt(i);}}catch(Exception e){android.util.Log.w("MTv-Player","Unable to clean player controls",e);}
        try{if(oldPlayer!=null)oldPlayer.release();}catch(Exception e){android.util.Log.w("MTv-Player","Player release failed",e);}
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
    private String readBoundedPlaylistLine(BufferedReader reader)throws IOException{
  StringBuilder line=new StringBuilder(256);
  int ch;
  while((ch=reader.read())!=-1){
   if(ch=='\n')break;
   if(ch!='\r'){
    if(line.length()>=1024*1024)throw new IOException("Ligne de playlist trop longue (limite 1 Mo)");
    line.append((char)ch);
   }
  }
  if(ch==-1&&line.length()==0)return null;
  return line.toString();
 }
 private int appendM3uPlaylist(String address,ArrayList<MediaEntry> merged,HashMap<String,HashSet<String>> seenStreamEntries,int request)throws Exception{
 HttpURLConnection connection=open(address);
 try{
  int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("Serveur HTTP "+code);
  InputStream input=connection.getInputStream();if(input==null)throw new IOException("Réponse vide du serveur");
  int count=0;long chars=0L;
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"))){
   String line,name=null,group="Autres",logo="",tvgId="";
   while((line=readBoundedPlaylistLine(reader))!=null&&merged.size()<MAX_CATALOG_ITEMS){
    if(request!=catalogRequestId||destroyed)throw new IOException("Chargement M3U annulé");
    chars+=line.length();if(chars>64L*1024L*1024L)throw new IOException("Playlist trop volumineuse (limite 64 Mo)");
    line=line.trim();if(line.startsWith("\uFEFF"))line=line.substring(1);
    if(line.startsWith("#EXTINF:")){
     name=line.substring(line.lastIndexOf(',')+1).trim();group="Autres";logo="";tvgId="";
     int g=line.indexOf("group-title=\"");if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}
     int tid=line.indexOf("tvg-id=\"");if(tid>=0){int te=line.indexOf('"',tid+8);if(te>tid)tvgId=line.substring(tid+8,te);}
     int l=line.indexOf("tvg-logo=\"");if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}
    }else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){
     if(line.startsWith("https://")||line.startsWith("http://")){String identity=name.trim().toLowerCase(Locale.ROOT)+"\u001F"+group.trim().toLowerCase(Locale.ROOT)+"\u001F"+tvgId.trim().toLowerCase(Locale.ROOT);HashSet<String> identities=seenStreamEntries.get(line);if(identities==null){identities=new HashSet<>();seenStreamEntries.put(line,identities);}if(identities.add(identity)){MediaEntry entry=new MediaEntry(name,line,group,logo,"m3u");entry.tvgId=tvgId;merged.add(entry);count++;}}
     name=null;tvgId="";
    }
   }
  }
  return count;
 }finally{connection.disconnect();}
}
private void loadAllPlaylists(){
 final int request=++catalogRequestId;ArrayList<String> urls=new ArrayList<>(playlistSources);if(urls.isEmpty())urls.add(DEFAULT_PLAYLIST);
 status.setText("Chargement de "+urls.size()+" source(s) M3U…");
 executeIo(()->{
  ArrayList<MediaEntry> merged=new ArrayList<>();HashMap<String,HashSet<String>> seenStreamEntries=new HashMap<>();ArrayList<String> errors=new ArrayList<>();ArrayList<String> sourceResults=new ArrayList<>();
  int completedSources=0;
  for(int sourceIndex=0;sourceIndex<urls.size();sourceIndex++){
   String address=urls.get(sourceIndex);
   if(merged.size()>=MAX_CATALOG_ITEMS||request!=catalogRequestId||destroyed)break;
   String host=Uri.parse(address).getHost();if(host==null||host.isEmpty())host="Source "+(sourceIndex+1);
   final String progressHost=host;final int progressIndex=sourceIndex+1;final int progressTotal=urls.size();
   postUi(()->{if(request==catalogRequestId&&status!=null)status.setText("Chargement M3U "+progressIndex+"/"+progressTotal+" • "+progressHost);});
   long startedAt=SystemClock.elapsedRealtime();int sourceCount=0;String outcome="OK";
   try{sourceCount=appendM3uPlaylist(address,merged,seenStreamEntries,request);}
   catch(Exception ex){outcome="ÉCHEC";String reason=ex.getMessage();if(reason==null||reason.trim().isEmpty())reason=ex.getClass().getSimpleName();errors.add(host+": "+reason);}
   long elapsed=Math.max(0L,SystemClock.elapsedRealtime()-startedAt);completedSources++;
   sourceResults.add(host+" : "+sourceCount+" élément(s), "+(elapsed/1000.0)+" s"+("OK".equals(outcome)?"":" • "+outcome));
  }
  if(request!=catalogRequestId||destroyed)return;final int loaded=merged.size();final int checkedSources=completedSources;final boolean capped=loaded>=MAX_CATALOG_ITEMS;
  StringBuilder summary=new StringBuilder().append(loaded).append(" éléments • ").append(checkedSources).append("/").append(urls.size()).append(" sources analysées");
  if(!errors.isEmpty())summary.append(" • ").append(errors.size()).append(" échec(s)");
  if(capped)summary.append(" • limite de ").append(MAX_CATALOG_ITEMS).append(" atteinte, sources restantes ignorées");
  if(!sourceResults.isEmpty()){summary.append(" • ");int shown=Math.min(4,sourceResults.size());for(int i=0;i<shown;i++){if(i>0)summary.append(" | ");summary.append(sourceResults.get(i));}if(sourceResults.size()>shown)summary.append(" | +").append(sourceResults.size()-shown).append(" autre(s)");}
  final String finalSummary=summary.toString();
  postUi(()->{if(request!=catalogRequestId)return;homeFeaturedCache=null;normalizedSearchIndex.clear();all.clear();all.addAll(merged);sourceMode="M3U";sourceLabel.setText("SOURCES : "+urls.size()+" M3U");
   if(loaded==0){String problem=errors.isEmpty()?"Aucune chaîne reçue. Vérifie tes sources.":"Erreur M3U : "+errors.get(0);status.setText(problem+" • "+finalSummary);renderList();onStartupPlaylistLoadFinished(false,"AUCUNE PLAYLIST DISPONIBLE • VÉRIFIE TES SOURCES");return;}
   status.setText(finalSummary);renderList();onStartupPlaylistLoadFinished(true,loaded+" CHAÎNES CHARGÉES • PRÊT À REGARDER");
  });
 });
}

    private String api(String action,String extra){String base=xtreamBase;if(!base.endsWith("/"))base+="/";return base+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass)+"&action="+action+(extra==null?"":extra);}
    private String enc(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private void loadXtream(){
 final int request=++catalogRequestId;status.setText("Connexion au serveur Xtream Codes…");
 executeIo(()->{
  ArrayList<MediaEntry> parsed=new ArrayList<>();ArrayList<String> endpointErrors=new ArrayList<>(),endpointReports=new ArrayList<>();String err=null;
  try{
   String auth=get(xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass));
   JSONObject root=new JSONObject(auth);JSONObject user=root.optJSONObject("user_info");if(user!=null&&!user.optString("auth","1").equals("1"))throw new IOException("Identifiants refusés par le serveur");
   ArrayList<String> categoryWarnings=new ArrayList<>();
   Map<String,String> liveCats=safeCategories("get_live_categories",categoryWarnings,"catégories TV"),vodCats=safeCategories("get_vod_categories",categoryWarnings,"catégories Films"),seriesCats=safeCategories("get_series_categories",categoryWarnings,"catégories Séries");
   String streamBase=xtreamBase+(xtreamBase.endsWith("/")?"":"/");String credentialsPath=enc(xtreamUser)+"/"+enc(xtreamPass)+"/";
   long endpointStarted=System.currentTimeMillis();int endpointBefore=parsed.size();
   try{parseXtreamArray(parsed,get(api("get_live_streams","")),"live","m3u8",streamBase+"live/"+credentialsPath,liveCats);endpointReports.add("TV "+(parsed.size()-endpointBefore)+" ("+((System.currentTimeMillis()-endpointStarted)/1000)+" s)");}catch(Exception e){endpointErrors.add("TV indisponible ("+e.getClass().getSimpleName()+")");}
   endpointStarted=System.currentTimeMillis();endpointBefore=parsed.size();
   if(parsed.size()<MAX_CATALOG_ITEMS){try{parseXtreamArray(parsed,get(api("get_vod_streams","")),"movie","mp4",streamBase+"movie/"+credentialsPath,vodCats);endpointReports.add("Films "+(parsed.size()-endpointBefore)+" ("+((System.currentTimeMillis()-endpointStarted)/1000)+" s)");}catch(Exception e){endpointErrors.add("Films indisponibles ("+e.getClass().getSimpleName()+")");}}else endpointReports.add("Films ignorés (limite atteinte)");
   endpointStarted=System.currentTimeMillis();endpointBefore=parsed.size();
   if(parsed.size()<MAX_CATALOG_ITEMS){try{parseXtreamArray(parsed,get(api("get_series","")),"series","mp4",streamBase+"series/"+credentialsPath,seriesCats);endpointReports.add("Séries "+(parsed.size()-endpointBefore)+" ("+((System.currentTimeMillis()-endpointStarted)/1000)+" s)");}catch(Exception e){endpointErrors.add("Séries indisponibles ("+e.getClass().getSimpleName()+")");}}else endpointReports.add("Séries ignorées (limite atteinte)");
   if(parsed.size()>=MAX_CATALOG_ITEMS)endpointErrors.add("Limite du catalogue atteinte ("+MAX_CATALOG_ITEMS+" éléments) : certaines rubriques peuvent être incomplètes");
   endpointErrors.addAll(categoryWarnings);
   if(parsed.isEmpty()&&!endpointErrors.isEmpty())throw new IOException(android.text.TextUtils.join(" • ",endpointErrors));
   if(parsed.isEmpty())throw new IOException("Le serveur n’a renvoyé aucun contenu TV, Films ou Séries. Vérifie les droits et les rubriques de ton compte.");
  }catch(Exception e){err=e.getMessage();}
  final String error=err;final ArrayList<String> failures=endpointErrors;if(request!=catalogRequestId||destroyed)return;
  postUi(()->{if(request!=catalogRequestId)return;if(error!=null){status.setText("Connexion Xtream impossible : "+error);return;}
   homeFeaturedCache=null;normalizedSearchIndex.clear();all.clear();all.addAll(parsed);sourceMode="XTREAM";sourceLabel.setText("SOURCE : XTREAM");
   StringBuilder summary=new StringBuilder().append(parsed.size()).append(" éléments • Xtream");if(!endpointReports.isEmpty())summary.append(" • ").append(android.text.TextUtils.join(" | ",endpointReports));if(!failures.isEmpty())summary.append(" • Alertes : ").append(android.text.TextUtils.join(", ",failures));status.setText(summary.toString());renderList();
  });
 });
}
    private Map<String,String> safeCategories(String action,ArrayList<String> warnings,String label){try{return parseCategories(get(api(action,"")));}catch(Exception e){warnings.add(label+" indisponibles ("+e.getClass().getSimpleName()+")");return new HashMap<>();}}
    private Map<String,String> parseCategories(String json)throws JSONException{Map<String,String> map=new HashMap<>();JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null){String id=o.optString("category_id","");if(!id.isEmpty())map.put(id,o.optString("category_name","Autres"));}}return map;}
    private void parseXtreamArray(ArrayList<MediaEntry> out,String json,String type,String fallback,String streamPrefix,Map<String,String> categories)throws JSONException{JSONArray a=new JSONArray(json);String fallbackCategory=type.equals("live")?"TV en direct":type.equals("movie")?"Films":"Séries";for(int i=0;i<a.length()&&out.size()<MAX_CATALOG_ITEMS;i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString(type.equals("series")?"series_id":"stream_id",o.optString("id",""));if(id.isEmpty())continue;String name=o.optString("name",type.equals("series")?"Série":"Contenu");String categoryId=o.optString("category_id");String cat=o.optString("category_name",categories.containsKey(categoryId)?categories.get(categoryId):fallbackCategory);String logo=o.optString("stream_icon",o.optString("cover",""));String ext=o.optString("container_extension",fallback);String path=streamPrefix+id+"."+ext;MediaEntry m=new MediaEntry(name,path,cat,logo,type);m.id=id;m.container=ext;m.tvgId=o.optString("epg_channel_id","");m.rating=o.optString("rating","");m.year=o.optString("year",o.optString("releaseDate",""));m.duration=o.optString("duration","");m.genre=o.optString("genre","");m.plot=o.optString("plot","");m.cast=o.optString("cast","");m.director=o.optString("director","");out.add(m);}}

    private void recordSuccessfulStream(String url){
        if(url==null||url.isEmpty()||prefs==null)return;
        if(recentlyPlayable.contains(url))return;
        recentlyPlayable.add(url);
        while(recentlyPlayable.size()>250)recentlyPlayable.remove(recentlyPlayable.iterator().next());
        prefs.edit().putStringSet("recently_playable",new HashSet<>(recentlyPlayable)).apply();
    }
    private void recordFailedStream(String url){
        if(url==null||url.isEmpty()||prefs==null)return;
        if(recentlyPlayable.remove(url))prefs.edit().putStringSet("recently_playable",new HashSet<>(recentlyPlayable)).apply();
    }
    private boolean hasHistory(MediaEntry c){if(c==null||c.url==null||c.url.isEmpty())return false;try{return historyKeysCache.contains("history_"+key(c.url));}catch(Exception ex){return false;}}
    private long historyPosition(MediaEntry c){if(c==null||c.url==null||prefs==null)return 0L;try{Object value=prefs.getAll().get("position_"+key(c.url));return value instanceof Number?Math.max(0L,((Number)value).longValue()):0L;}catch(Exception ex){return 0L;}}
    private boolean matchSection(MediaEntry c){
        if(c==null||c.url==null||c.url.isEmpty())return false;
        if(section.equals("MTV")) return mtvItems.contains(c);
        if(section.equals("MUSIQUE"))return isMusicEntry(c);
        if(section.equals("INFOS"))return isNewsEntry(c);
        String g=c.group==null?"":c.group.toLowerCase(Locale.ROOT),n=c.name==null?"":c.name.toLowerCase(Locale.ROOT);if(section.equals("FAVORIS")){if(!favorites.contains(c.url))return false;if(activeFilter!=null&&!activeFilter.equals("Tout")){Set<String> group=favoriteGroups.get(activeFilter);return group!=null&&group.contains(c.url);}return true;}if(section.equals("HISTORIQUE"))return hasHistory(c);if(section.equals("GUIDE TV"))return (c.type.equals("live")||c.type.equals("m3u"))&&!c.tvgId.isEmpty();String type=c.type==null?"":c.type;if(section.equals("TV EN DIRECT"))return type.equals("live")||type.equals("m3u");if(section.equals("FILMS"))return type.equals("movie")||g.contains("movie")||g.contains("film")||g.contains("vod");if(section.equals("SÉRIES"))return type.equals("series")||g.contains("series");if(section.equals("ANIMÉS"))return g.contains("anime")||g.contains("animation")||g.contains("cartoon")||n.contains("anime");
        String categoryText=g+" "+n;
        if(section.equals("SPORTS"))return matchesAny(categoryText,"sport","football","soccer","basket","tennis","rugby","cricket","formula 1","f1","motogp","volley","boxing","wrestling");
        if(section.equals("CINÉMA"))return c.type.equals("movie")||matchesAny(categoryText,"cinema","cinéma","movie","film","vod");
        if(section.equals("DIVERTISSEMENT"))return matchesAny(categoryText,"entertainment","divertissement","variety","variete","reality","show","talk show","comedy","humour","game show");
        if(section.equals("JEUNESSE"))return matchesAny(categoryText,"kids","children","jeunesse","junior","cartoon","child","preschool","family");
        if(section.equals("MAGAZINE"))return matchesAny(categoryText,"magazine","lifestyle","fashion","people","actualité magazine","current affairs");
        if(section.equals("DOCUMENTAIRE"))return matchesAny(categoryText,"documentary","documentaire","discovery","history","nature","wildlife","science doc");
        if(section.equals("ÉDUCATION"))return matchesAny(categoryText,"education","éducation","educational","learning","school","university","formation","cours");
        if(section.equals("RELIGION"))return matchesAny(categoryText,"religion","religious","gospel","church","islam","christ","faith","coran","bible");
        if(section.equals("SCIENCE & TECHNOLOGIE"))return matchesAny(categoryText,"science","technology","technologie","tech","innovation","engineering","informatique","space","astronomy");
        if(section.equals("NOVELAS"))return matchesAny(categoryText,"novela","novelas","telenovela","soap opera","drama series");
        return true;}
    private boolean filterMatches(String filter,String group,String name){
        if(filter==null||filter.equals("Tout"))return true;
        String value=((group==null?"":group)+" "+(name==null?"":name)).toLowerCase(Locale.ROOT);
        String f=filter.toLowerCase(Locale.ROOT);
        String[][] aliases={
            {"afrique","africa","african","west africa","east africa","north africa","south africa","central africa","togo","benin","bénin","côte d’ivoire","cote d’ivoire","ivory coast","ghana","nigeria","senegal","sénégal","cameroon","cameroun","congo","rdc","dr congo","morocco","maroc","algeria","algérie","tunisia","tunisie","egypt","égypte","kenya","ouganda","uganda","tanzanie","tanzania","éthiopie","ethiopia","rwanda","zambie","zambia"},
            {"europe","european","france","french","français","royaume-uni","united kingdom","britain","england","uk","allemagne","germany","deutschland","espagne","spain","italie","italy","portugal","belgique","belgium","pays-bas","netherlands","suisse","switzerland","autriche","austria","suède","sweden","norvège","norway","danemark","denmark","grèce","greece","pologne","poland","russie","russia"},
            {"asie","asia","asian","middle east","moyen-orient","inde","india","chine","china","japon","japan","corée du sud","south korea","korea","turquie","turkey","arabie saoudite","saudi arabia","émirats arabes unis","uae","pakistan","bangladesh","indonésie","indonesia","philippines","thaïlande","thailand","vietnam","malaisie","malaysia","singapour","singapore"},
            {"amérique du nord","north america","north-america","nord-améric","usa","united states","canada","mexique","mexico"},
            {"amérique du sud","south america","latin america","latino","sud-améric","brésil","brazil","argentine","argentina","chili","chile","colombie","colombia","pérou","peru","uruguay","équateur","ecuador"},
            {"océanie","oceania","australia","new zealand","pacific","australie","nouvelle-zélande","fidji","fiji","papouasie-nouvelle-guinée","papua new guinea"},
            {"france","french","français","française"},
            {"togo","togolese"},
            {"bénin","benin","beninese"},
            {"côte d’ivoire","cote d'ivoire","ivory coast","ivoir"},
            {"ghana","ghanaian"},
            {"nigeria","nigerian"},
            {"sénégal","senegal","senegalese"},
            {"cameroun","cameroon","cameroonian"},
            {"congo","republic of the congo"},
            {"rdc","dr congo","drc","democratic republic of the congo"},
            {"afrique du sud","south africa","south african"},
            {"maroc","morocco","moroccan"},
            {"algérie","algeria","algerian"},
            {"tunisie","tunisia","tunisian"},
            {"égypte","egypt","egyptian"},
            {"royaume-uni","united kingdom","britain","british","england","uk"},
            {"allemagne","germany","deutschland","german"},
            {"espagne","spain","españa","spanish"},
            {"italie","italy","italian"},
            {"portugal","portuguese"},
            {"belgique","belgium","belgian"},
            {"pays-bas","netherlands","dutch","holland"},
            {"suisse","switzerland","swiss"},
            {"usa","united states","u.s.","america","american"},
            {"canada","canadian"},
            {"mexique","mexico","mexican"},
            {"brésil","brazil","brazilian"},
            {"argentine","argentina"},
            {"chili","chile","chilean"},
            {"inde","india","indian"},
            {"chine","china","chinese"},
            {"japon","japan","japanese"},
            {"corée du sud","south korea","korea","korean"},
            {"turquie","turkey","turkish"},
            {"arabie saoudite","saudi arabia","saudi"},
            {"émirats arabes unis","united arab emirates","uae","emirates"},
            {"australie","australia","australian"},
            {"nouvelle-zélande","new zealand"},
            {"kenya","kenyan"},
            {"ouganda","uganda","ugandan"},
            {"tanzanie","tanzania","tanzanian"},
            {"éthiopie","ethiopia","ethiopian"},
            {"rwanda","rwandan"},
            {"zambie","zambia","zambian"},
            {"autriche","austria","austrian"},
            {"suède","sweden","swedish"},
            {"norvège","norway","norwegian"},
            {"danemark","denmark","danish"},
            {"grèce","greece","greek"},
            {"pologne","poland","polish"},
            {"russie","russia","russian"},
            {"pakistan","pakistani"},
            {"bangladesh","bangladeshi"},
            {"indonésie","indonesia","indonesian"},
            {"philippines","philippine"},
            {"thaïlande","thailand","thai"},
            {"vietnam","vietnamese"},
            {"malaisie","malaysia","malaysian"},
            {"singapour","singapore"},
            {"colombie","colombia","colombian"},
            {"pérou","peru","peruvian"},
            {"uruguay","uruguayan"},
            {"équateur","ecuador","ecuadorian"},
            {"fidji","fiji","fijian"},
            {"papouasie-nouvelle-guinée","papua new guinea"}
        };
        for(String[] groupAliases:aliases)if(groupAliases[0].equalsIgnoreCase(filter)){
            for(String alias:groupAliases)if(value.contains(alias))return true;
            return false;
        }
        return value.contains(f);
    }
    private boolean matchesAny(String value,String... terms){
        if(value==null)return false;
        String v=value.toLowerCase(Locale.ROOT);
        for(String term:terms)if(v.contains(term))return true;
        return false;
    }
    private void stylePlayerButton(TextView button,boolean focused){
        if(focused){GradientDrawable d=bg(FOCUS_GOLD,10);d.setStroke(dp(2),FOCUS_RED);button.setBackground(d);button.setTextColor(0xFF17111A);button.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110).start();}
        else{button.setBackground(bg(CARD,10));button.setTextColor(Color.WHITE);button.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}
    }
    private TextView playerControl(String glyph,String description,Runnable action){
        TextView button=text(glyph,19,Color.WHITE,true);button.setGravity(Gravity.CENTER);button.setContentDescription(description);button.setFocusable(true);button.setClickable(true);button.setBackground(bg(CARD,10));
        button.setOnFocusChangeListener((v,focused)->stylePlayerButton((TextView)v,focused));button.setOnClickListener(v->{if(action!=null)action.run();});return button;
    }
    private String homeCategoriesKey(){return PROFILE_CHILD.equals(activeProfile)?"home_categories_child":"home_categories_adult";}
    private String[][] homeQuickCategories(){
        return new String[][]{{"▶","TV EN DIRECT","TV EN DIRECT","0xFF176B91"},{"⚽","SPORTS","SPORTS","0xFF187B65"},{"🎬","CINÉMA","CINÉMA","0xFF813B8E"},{"🎭","DIVERTISSEMENT","DIVERTISSEMENT","0xFFB64E4E"},{"🎥","FILMS","FILMS","0xFF3456A1"},{"▤","SÉRIES","SÉRIES","0xFF9A5A24"},{"♫","MUSIQUE","MUSIQUE","0xFF5A4BA5"},{"🧒","JEUNESSE","JEUNESSE","0xFFB35C87"},{"▧","DOCUMENTAIRE","DOCUMENTAIRE","0xFF397A82"},{"▦","GUIDE TV","GUIDE TV","0xFF586A32"}};
    }
    private Set<String> getHomeCategories(){
        Set<String> defaults=new LinkedHashSet<>();
        for(String[] item:homeQuickCategories())defaults.add(item[2]);
        Set<String> stored=prefs==null?null:prefs.getStringSet(homeCategoriesKey(),null);
        if(stored==null)return defaults;
        Set<String> valid=new LinkedHashSet<>();
        for(String[] item:homeQuickCategories())if(stored.contains(item[2]))valid.add(item[2]);
        return valid.isEmpty()?defaults:valid;
    }
    private void showHomePersonalizationDialog(){
        String[][] items=homeQuickCategories();
        String[] labels=new String[items.length];boolean[] checked=new boolean[items.length];
        Set<String> selected=getHomeCategories();
        for(int i=0;i<items.length;i++){labels[i]=items[i][1];checked[i]=selected.contains(items[i][2]);}
        new AlertDialog.Builder(this).setTitle("Personnaliser mon accueil")
            .setMessage("Choisis les rubriques affichées dans les raccourcis de ce profil. Les choix Adulte et Enfant sont indépendants.")
            .setMultiChoiceItems(labels,checked,(dialog,which,isChecked)->checked[which]=isChecked)
            .setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(dialog,which)->{
                Set<String> chosen=new LinkedHashSet<>();
                for(int i=0;i<items.length;i++)if(checked[i])chosen.add(items[i][2]);
                if(chosen.isEmpty()){Toast.makeText(this,"Sélectionne au moins une rubrique.",Toast.LENGTH_LONG).show();return;}
                prefs.edit().putStringSet(homeCategoriesKey(),chosen).apply();
                renderList();
            }).show();
    }
    private void renderHome(){
        list.removeAllViews();
        LinearLayout hero=row();hero.setGravity(Gravity.CENTER_VERTICAL);GradientDrawable heroBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF43236F,0xFF12566B,0xFF15304E});heroBg.setCornerRadius(dp(20));hero.setBackground(heroBg);hero.setPadding(dp(18),dp(13),dp(16),dp(13));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(190));hp.topMargin=dp(10);hp.bottomMargin=dp(14);list.addView(hero,hp);
        LinearLayout heroCopy=column();hero.addView(heroCopy,new LinearLayout.LayoutParams(0,-1,1f));
        TextView heroTag=text("✦  À LA UNE  •  MTv",11,0xFFFFD66E,true);heroCopy.addView(heroTag,new LinearLayout.LayoutParams(-1,dp(25)));
        TextView heroTitle=text("Le meilleur du divertissement, en un seul endroit.",19,Color.WHITE,true);heroTitle.setMaxLines(2);heroCopy.addView(heroTitle,new LinearLayout.LayoutParams(-1,dp(55)));
        TextView heroSub=text("Explore, choisis et profite de tes programmes.",11,0xFFE3EAF8,false);heroCopy.addView(heroSub,new LinearLayout.LayoutParams(-1,dp(25)));
        TextView discover=text("▶  EXPLORER",11,0xFF17111A,true);discover.setGravity(Gravity.CENTER);discover.setBackground(bg(0xFFFFC247,10));discover.setFocusable(true);discover.setClickable(true);LinearLayout.LayoutParams discoverP=new LinearLayout.LayoutParams(dp(124),dp(40));discoverP.topMargin=dp(5);discoverP.bottomMargin=dp(3);heroCopy.addView(discover,discoverP);discover.setOnFocusChangeListener((v,focus)->stylePlayerButton((TextView)v,focus));discover.setOnClickListener(v->navigateTo("TV EN DIRECT"));
        LinearLayout art=column();art.setGravity(Gravity.CENTER);GradientDrawable artBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFFFFB52E,0xFFFF6A45,0xFF8B3DAB,0xFF2757A5});artBg.setCornerRadius(dp(22));art.setBackground(artBg);LinearLayout.LayoutParams artP=new LinearLayout.LayoutParams(dp(112),dp(124));artP.leftMargin=dp(10);hero.addView(art,artP);
        TextView artIcon=text("▶",36,Color.WHITE,true);artIcon.setGravity(Gravity.CENTER);art.addView(artIcon,new LinearLayout.LayoutParams(-1,dp(68)));
        TextView artBrand=text("MTv  •  LIVE",10,Color.WHITE,true);artBrand.setGravity(Gravity.CENTER);art.addView(artBrand,new LinearLayout.LayoutParams(-1,dp(24)));
        TextView catTitle=text("EXPLORER LES RUBRIQUES  •  PERSONNALISER",12,0xFFFFC45C,true);catTitle.setLetterSpacing(0.04f);catTitle.setFocusable(true);catTitle.setClickable(true);catTitle.setOnClickListener(v->showHomePersonalizationDialog());catTitle.setOnFocusChangeListener((v,focus)->{if(focus){((TextView)v).setTextColor(FOCUS_GOLD);v.setBackground(bg(PANEL,6));}else{((TextView)v).setTextColor(0xFFFFC45C);v.setBackgroundColor(Color.TRANSPARENT);}});list.addView(catTitle,new LinearLayout.LayoutParams(-1,dp(29)));
        HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);categoryScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);LinearLayout categoryRow=row();categoryScroll.addView(categoryRow);list.addView(categoryScroll,new LinearLayout.LayoutParams(-1,dp(100)));
        String[][] quick=homeQuickCategories();Set<String> visibleHomeCategories=getHomeCategories();
        for(String[] item:quick){if(!visibleHomeCategories.contains(item[2]))continue;LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(7),dp(7),dp(7),dp(5));GradientDrawable tileBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.parseColor("#"+item[3].substring(2)),0xFF17243D});tileBg.setCornerRadius(dp(13));tile.setBackground(tileBg);tile.setFocusable(true);tile.setClickable(true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(104),dp(84));tp.rightMargin=dp(9);categoryRow.addView(tile,tp);
            TextView glyph=text(item[0],25,Color.WHITE,true);glyph.setGravity(Gravity.CENTER);tile.addView(glyph,new LinearLayout.LayoutParams(-1,dp(43)));
            TextView label=text(item[1],9,Color.WHITE,true);label.setGravity(Gravity.CENTER);label.setMaxLines(2);tile.addView(label,new LinearLayout.LayoutParams(-1,dp(27)));
            tile.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(0xFFFFC247,13);d.setStroke(dp(3),0xFFFF7A00);v.setBackground(d);v.animate().scaleX(1.055f).scaleY(1.055f).setDuration(110).start();}else{v.setBackground(tileBg);v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});
            tile.setOnClickListener(v->navigateTo(item[2]));
        }
        TextView featuredTitle=text("À DÉCOUVRIR  •  PROGRAMMES & DIRECTS",12,0xFFFFC45C,true);featuredTitle.setLetterSpacing(0.06f);LinearLayout.LayoutParams ft=new LinearLayout.LayoutParams(-1,dp(34));ft.topMargin=dp(9);list.addView(featuredTitle,ft);
        HorizontalScrollView featuredScroll=new HorizontalScrollView(this);featuredScroll.setHorizontalScrollBarEnabled(false);LinearLayout featured=row();featuredScroll.addView(featured);list.addView(featuredScroll,new LinearLayout.LayoutParams(-1,dp(188)));
        if(all==null||all.isEmpty()){TextView empty=text("Charge une playlist pour découvrir les chaînes et programmes.",12,MUTED,false);list.addView(empty);return;}
        if(homeFeaturedCache==null){
            homeFeaturedCache=new ArrayList<>();
            String homeSection=section;
            String[] featuredSections={"TV EN DIRECT","SPORTS","FILMS","CINÉMA","SÉRIES","DIVERTISSEMENT","INFOS","MUSIQUE","DOCUMENTAIRE","MAGAZINE","JEUNESSE","NOVELAS","ANIMÉS","ÉDUCATION","RELIGION","SCIENCE & TECHNOLOGIE"};
            HashSet<String> featuredUrls=new HashSet<>();
            try{for(String category:featuredSections){section=category;for(MediaEntry entry:all){if(entry==null||entry.name==null||entry.name.isEmpty()||entry.url==null||featuredUrls.contains(entry.url))continue;if(matchSection(entry)){homeFeaturedCache.add(entry);featuredUrls.add(entry.url);break;}}}}finally{section=homeSection;}
        }
        ArrayList<MediaEntry> featuredEntries=homeFeaturedCache;
        int count=0;
        for(MediaEntry entry:featuredEntries){
            LinearLayout card=column();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(dp(8),dp(8),dp(8),dp(6));card.setBackground(bg(PANEL,12));card.setFocusable(true);card.setClickable(true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(174),dp(172));cp.rightMargin=dp(10);featured.addView(card,cp);
            ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackground(bg(CARD,8));card.addView(logo,new LinearLayout.LayoutParams(dp(158),dp(102)));if(entry.logo!=null&&!entry.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(entry.logo).override(dp(158),dp(102)).fitCenter().dontAnimate().placeholder(bg(CARD,8)).error(bg(CARD,8)).into(logo);
            TextView label=text(entry.name,12,Color.WHITE,true);label.setGravity(Gravity.CENTER);label.setMaxLines(1);card.addView(label,new LinearLayout.LayoutParams(-1,dp(25)));String programme=findEpgProgram(epgNow,entry);String cardDetail=!programme.isEmpty()?"EN COURS • "+programme:(("live".equals(entry.type)||"m3u".equals(entry.type))?"EN DIRECT":(entry.group==null?"À DÉCOUVRIR":entry.group));TextView sub=text(cardDetail,9,0xFFB8C8DE,true);sub.setGravity(Gravity.CENTER);sub.setMaxLines(1);card.addView(sub,new LinearLayout.LayoutParams(-1,dp(20)));
            card.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(0xFF3A315D,12);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(110).start();}else{v.setBackground(bg(PANEL,12));v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});
            card.setOnClickListener(v->play(entry));count++;
        }
        if(count==0){TextView empty=text("Tes chaînes apparaîtront ici dès que la playlist sera chargée.",12,MUTED,false);list.addView(empty);}
    }
    private void renderHistory(){
        try{renderHistoryContent();}catch(Exception historyError){
            try{if(list!=null){list.removeAllViews();TextView fallback=text("Impossible d’afficher l’historique pour le moment. Réessaie après le rechargement des chaînes.",13,MUTED,false);fallback.setPadding(dp(12),dp(24),dp(12),dp(20));list.addView(fallback);}}catch(Exception ignored){}
            android.util.Log.e("MTv-History","History rendering failed",historyError);
        }
    }
    private void renderHistoryContent(){
        if(list==null)return;
        list.removeAllViews();
        Map<String,?> saved;
        try{saved=prefs==null?Collections.emptyMap():prefs.getAll();}
        catch(Exception ex){saved=Collections.emptyMap();}
        historyKeysCache.clear();
        for(String prefKey:saved.keySet())if(prefKey!=null&&prefKey.startsWith("history_"))historyKeysCache.add(prefKey);
        String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<MediaEntry> entries=new ArrayList<>();
        for(MediaEntry entry:all){
            if(entry==null||entry.url==null||entry.url.isEmpty())continue;
            String historyKey="history_"+key(entry.url);
            if(!saved.containsKey(historyKey))continue;
            if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(entry))continue;
            String name=entry.name==null?"":entry.name;
            String group=entry.group==null?"":entry.group;
            String current=findEpgProgram(epgNow,entry),next=findEpgProgram(epgNext,entry);
            if(!matchesSearchFields(query,name,group,current,next,entry.type))continue;
            if(!filterMatches(activeFilter,group,name))continue;
            entries.add(entry);
        }
        Set<String> catalogUrls=new HashSet<>();
        for(MediaEntry catalogEntry:all)if(catalogEntry!=null&&catalogEntry.url!=null)catalogUrls.add(catalogEntry.url);
        for(String prefKey:saved.keySet()){
            if(prefKey==null||!prefKey.startsWith("history_url_"))continue;
            String entryKey=prefKey.substring("history_url_".length());
            Object storedUrl=saved.get(prefKey);
            if(!(storedUrl instanceof String))continue;
            String url=(String)storedUrl;
            if(url.isEmpty()||catalogUrls.contains(url))continue;
            Object storedName=saved.get("history_"+entryKey);
            String restoredName=storedName instanceof String?(String)storedName:"Contenu";
            Object storedType=saved.get("history_type_"+entryKey);
            String restoredType=storedType instanceof String?(String)storedType:"episode";
            Object storedGroup=saved.get("history_group_"+entryKey);
            String restoredGroup=storedGroup instanceof String?(String)storedGroup:"";
            MediaEntry restored=new MediaEntry(restoredName,url,restoredGroup,"",restoredType);
            Object series=saved.get("history_series_"+entryKey);
            restored.seriesTitle=series instanceof String?(String)series:"";
            Object season=saved.get("history_season_"+entryKey);
            restored.seasonNumber=season instanceof Number?((Number)season).intValue():-1;
            Object episode=saved.get("history_episode_"+entryKey);
            restored.episodeNumber=episode instanceof Number?((Number)episode).intValue():-1;
            if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(restored))continue;
            if(!matchesSearchFields(query,restored.name,restored.group,restored.seriesTitle,restored.type))continue;
            if(!filterMatches(activeFilter,restored.group,restored.name))continue;
            entries.add(restored);
        }
        final Map<String,?> historySnapshot=saved;
        Collections.sort(entries,(left,right)->{
            Object leftValue=historySnapshot.get("history_time_"+key(left.url));
            Object rightValue=historySnapshot.get("history_time_"+key(right.url));
            long leftTime=leftValue instanceof Number?((Number)leftValue).longValue():0L;
            long rightTime=rightValue instanceof Number?((Number)rightValue).longValue():0L;
            return Long.compare(rightTime,leftTime);
        });
        String last=saved.get("last_url") instanceof String?(String)saved.get("last_url"):"";
        if(!last.isEmpty())for(int i=0;i<entries.size();i++)if(last.equals(entries.get(i).url)){MediaEntry mostRecent=entries.remove(i);entries.add(0,mostRecent);break;}
        TextView heading=text("REPRENDRE LA LECTURE",12,0xFFFFC45C,true);heading.setLetterSpacing(0.06f);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(32));hp.bottomMargin=dp(5);list.addView(heading,hp);
        int shown=0;int limit=Math.min(80,Math.max(1,maxRenderItems));
        for(MediaEntry entry:entries){
            if(shown>=limit)break;
            String name=entry.name==null||entry.name.trim().isEmpty()?"Contenu":entry.name.trim();
            String group=entry.group==null?"":entry.group;
            String type=entry.type==null?"":entry.type;
            long position=0L;
            try{Object stored=saved.get("position_"+key(entry.url));if(stored instanceof Number)position=Math.max(0L,((Number)stored).longValue());}catch(Exception ignored){}
            String detail;
            if("live".equals(type)||"m3u".equals(type)){
                detail=group+"  •  En direct  •  Récemment regardé";
            }else{
                if("episode".equals(type)){
                    String episodeLabel=(entry.seasonNumber>0?"S"+entry.seasonNumber:"Saison ?")
                        +(entry.episodeNumber>0?" • E"+entry.episodeNumber:"");
                    detail=(entry.seriesTitle==null||entry.seriesTitle.isEmpty()?"Série":entry.seriesTitle)
                        +"  •  "+episodeLabel
                        +(position>0L?"  •  Reprendre à "+(position/60000)+" min":"  •  À regarder");
                }else{
                    detail=group+"  •  "+("series".equals(type)?"Série":"VOD")
                        +(position>0L?"  •  Reprendre à "+(position/60000)+" min":"  •  À regarder");
                }
            }
            LinearLayout item=row();item.setPadding(dp(12),dp(5),dp(12),dp(5));item.setBackground(bg(PANEL,11));item.setFocusable(true);item.setClickable(true);
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(64));ip.bottomMargin=dp(7);list.addView(item,ip);if(chips!=null&&chips.getChildCount()>0)item.setNextFocusUpId(chips.getChildAt(0).getId());
            TextView symbol=text(("live".equals(type)||"m3u".equals(type))?"TV":"▶",16,0xFFFFC247,true);symbol.setGravity(Gravity.CENTER);symbol.setBackground(bg(CARD,8));item.addView(symbol,new LinearLayout.LayoutParams(dp(54),dp(48)));
            LinearLayout meta=column();meta.setGravity(Gravity.CENTER_VERTICAL);meta.setPadding(dp(12),0,0,0);item.addView(meta,new LinearLayout.LayoutParams(0,-1,1f));
            TextView title=text(name,13,Color.WHITE,true);title.setMaxLines(1);meta.addView(title,new LinearLayout.LayoutParams(-1,dp(24)));
            TextView subtitle=text(detail,10,MUTED,false);subtitle.setMaxLines(1);meta.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(20)));
            item.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(ACCENT,11);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);}else v.setBackground(bg(PANEL,11));});
            item.setOnClickListener(v->openContent(entry));
            shown++;
        }
        if(shown==0){TextView empty=text("Aucun historique disponible pour cette source. Les contenus apparaîtront ici après une lecture.",14,MUTED,false);empty.setPadding(dp(12),dp(28),dp(12),dp(20));list.addView(empty);}
    }
    private void renderEpgGuide(){
        if(list==null)return;
        list.removeAllViews();
        String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        LinearLayout header=row();
        TextView title=text("GUIDE TV",17,Color.WHITE,true);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(42),1f));
        TextView refresh=text("↻  Actualiser",11,Color.WHITE,true);
        refresh.setGravity(Gravity.CENTER);refresh.setPadding(dp(12),0,dp(12),0);
        refresh.setBackground(bg(CARD,9));refresh.setFocusable(true);refresh.setClickable(true);
        header.addView(refresh,new LinearLayout.LayoutParams(-2,dp(36)));
        refresh.setOnFocusChangeListener((v,focused)->v.setBackground(bg(focused?FOCUS_GOLD:CARD,9)));
        refresh.setOnClickListener(v->{if(epgUrl!=null&&!epgUrl.isEmpty())loadEpg(epgUrl);else showEpgDialog();});
        TextView epgFilter=text(showOnlyEpgChannels?"✓ Avec EPG":"Toutes",10,Color.WHITE,true);
        epgFilter.setGravity(Gravity.CENTER);epgFilter.setPadding(dp(10),0,dp(10),0);epgFilter.setBackground(bg(showOnlyEpgChannels?0xFF76551C:CARD,9));epgFilter.setFocusable(true);epgFilter.setClickable(true);
        LinearLayout.LayoutParams epgFilterParams=new LinearLayout.LayoutParams(-2,dp(36));epgFilterParams.leftMargin=dp(7);header.addView(epgFilter,epgFilterParams);
        epgFilter.setOnFocusChangeListener((v,focused)->v.setBackground(bg(focused?FOCUS_GOLD:(showOnlyEpgChannels?0xFF76551C:CARD),9)));
        epgFilter.setOnClickListener(v->{showOnlyEpgChannels=!showOnlyEpgChannels;renderEpgGuide();});
        TextView source=text(epgUrl==null||epgUrl.isEmpty()?"Configurer XMLTV":"Source XMLTV",11,Color.WHITE,true);
        source.setGravity(Gravity.CENTER);source.setPadding(dp(12),0,dp(12),0);source.setBackground(bg(CARD,9));
        source.setFocusable(true);source.setClickable(true);
        LinearLayout.LayoutParams sourceParams=new LinearLayout.LayoutParams(-2,dp(36));sourceParams.leftMargin=dp(8);header.addView(source,sourceParams);
        source.setOnFocusChangeListener((v,focused)->v.setBackground(bg(focused?FOCUS_GOLD:CARD,9)));
        source.setOnClickListener(v->showEpgDialog());
        list.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        int mappedNow=epgNow.size(),mappedNext=epgNext.size();
        String guideStatus=epgUrl==null||epgUrl.isEmpty()?"Aucune source XMLTV configurée.":"Programmes disponibles : "+mappedNow+" en cours • "+mappedNext+" à suivre";
        TextView summary=text(guideStatus,11,MUTED,false);summary.setPadding(0,0,0,dp(8));
        list.addView(summary,new LinearLayout.LayoutParams(-1,dp(30)));
        if(all==null||all.isEmpty()){
            TextView empty=text("Charge une playlist pour afficher les chaînes dans le Guide TV.",13,MUTED,false);
            empty.setPadding(dp(8),dp(20),dp(8),dp(20));list.addView(empty);return;
        }
        int shown=0,matched=0;HashSet<String> seen=new HashSet<>();
        for(MediaEntry entry:all){
            if(entry==null||entry.url==null||entry.name==null)continue;
            if(!"live".equals(entry.type)&&!"m3u".equals(entry.type))continue;
            if(!seen.add(entry.url))continue;
            if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(entry))continue;
            String name=entry.name.trim();
            String current=findEpgProgram(epgNow,entry),next=findEpgProgram(epgNext,entry);
            boolean hasEpg=!current.isEmpty()||!next.isEmpty();
            if(showOnlyEpgChannels&&!hasEpg)continue;
            if(!matchesSearchFields(query,name,entry.group,current,next,entry.type))continue;
            if(!filterMatches(activeFilter,entry.group,entry.name))continue;
            matched++;if(shown>=maxRenderItems)continue;
            LinearLayout card=row();card.setPadding(dp(10),dp(7),dp(10),dp(7));
            card.setBackground(bg(PANEL,11));card.setFocusable(true);card.setClickable(true);
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,dp(78));cardParams.bottomMargin=dp(7);list.addView(card,cardParams);
            ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackground(bg(CARD,8));
            card.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(58)));
            if(entry.logo!=null&&!entry.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(entry.logo).override(dp(64),dp(58)).fitCenter().dontAnimate().placeholder(bg(CARD,8)).error(bg(CARD,8)).into(logo);
            LinearLayout details=column();details.setGravity(Gravity.CENTER_VERTICAL);details.setPadding(dp(11),0,dp(4),0);
            card.addView(details,new LinearLayout.LayoutParams(0,-1,1f));
            TextView channelName=text(name,13,Color.WHITE,true);channelName.setMaxLines(1);
            details.addView(channelName,new LinearLayout.LayoutParams(-1,dp(21)));
            String currentStart=epgTimeLabel(epgNowStart,entry),currentStop=epgTimeLabel(epgNowStop,entry),nextStartLabel=epgTimeLabel(epgNextStart,entry);
            String currentTime=currentStart.isEmpty()||currentStop.isEmpty()?"":currentStart+"–"+currentStop+"  •  ";
            TextView nowLine=text(current.isEmpty()?"EN COURS  •  Programme non renseigné":"EN COURS  •  "+currentTime+current,10,current.isEmpty()?MUTED:0xFFFFD16B,true);
            nowLine.setMaxLines(1);details.addView(nowLine,new LinearLayout.LayoutParams(-1,dp(19)));
            android.widget.ProgressBar progressBar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);long startMs=epgTimeValue(epgNowStart,entry),stopMs=epgTimeValue(epgNowStop,entry),nowTime=System.currentTimeMillis();
            int progress=startMs>0L&&stopMs>startMs?(int)Math.max(0L,Math.min(100L,(nowTime-startMs)*100L/(stopMs-startMs))):0;
            progressBar.setProgress(progress);progressBar.setIndeterminate(false);
            LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(4));progressParams.topMargin=dp(1);progressParams.bottomMargin=dp(1);
            details.addView(progressBar,progressParams);
            TextView nextLine=text(next.isEmpty()?"ENSUITE  •  Programme non renseigné":"ENSUITE  •  "+(nextStartLabel.isEmpty()?"":nextStartLabel+"  •  ")+next,9,MUTED,false);
            nextLine.setMaxLines(1);details.addView(nextLine,new LinearLayout.LayoutParams(-1,dp(17)));
            TextView playIcon=text("▶",16,0xFFFFC247,true);playIcon.setGravity(Gravity.CENTER);card.addView(playIcon,new LinearLayout.LayoutParams(dp(34),-1));
            card.setOnFocusChangeListener((v,focused)->{if(focused){GradientDrawable d=bg(0xFF3A315D,11);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);}else v.setBackground(bg(PANEL,11));});
            card.setOnClickListener(v->play(entry));shown++;
        }
        if(matched==0){
            TextView empty=text(query.isEmpty()?"Aucune chaîne en direct disponible pour le guide.":"Aucune chaîne ne correspond à cette recherche ou à ce filtre.",13,MUTED,false);
            empty.setPadding(dp(8),dp(22),dp(8),dp(20));list.addView(empty);
        }else if(matched>shown){
            TextView more=text("Affichage limité à "+shown+" chaînes pour préserver la fluidité de l'appareil.",10,MUTED,false);
            more.setPadding(dp(8),dp(10),dp(8),dp(10));list.addView(more);
        }
    }
    private void renderList(){if(list==null)return;if(section.equals("HISTORIQUE")){renderHistory();return;}if(section.equals("GUIDE TV")){renderEpgGuide();return;}String restoreFocusUrl=focusedCatalogItemUrl();list.removeAllViews();String q=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);if(section.equals("ACCUEIL")&&q.trim().isEmpty()){renderHome();return;}int shown=0;boolean universalSearch=!q.trim().isEmpty();List<MediaEntry> activeItems=universalSearch?all:(section.equals("MTV")?mtvItems:all);
        if(!universalSearch){ArrayList<MediaEntry> preferredItems=new ArrayList<>(),otherItems=new ArrayList<>();HashSet<String> seenUrls=new HashSet<>();for(MediaEntry entry:activeItems){if(entry==null||entry.url==null||!seenUrls.add(entry.url))continue;if(recentlyPlayable.contains(entry.url))preferredItems.add(entry);else otherItems.add(entry);}preferredItems.addAll(otherItems);activeItems=preferredItems;}
        else if(section.equals("MTV")){ArrayList<MediaEntry> searchItems=new ArrayList<>(activeItems);searchItems.addAll(mtvItems);activeItems=searchItems;}
        HashSet<String> seenSearchUrls=universalSearch?new HashSet<>():null;
        for(MediaEntry c:activeItems){if(c==null||c.url==null)continue;if(universalSearch&&!seenSearchUrls.add(c.url))continue;if(!universalSearch&&!matchSection(c))continue;
            if(PROFILE_CHILD.equals(activeProfile) && isAdultEntry(c)) continue;
            String itemName=c.name==null?"":c.name;String itemGroup=c.group==null?"":c.group;
            if(!q.isEmpty()&&!matchesUniversalSearch(c,q))continue;if(!universalSearch&&!filterMatches(activeFilter,itemGroup,itemName))continue;
            LinearLayout item=row();item.setTag(c.url);item.setId(View.generateViewId());item.setPadding(dp(13),dp(4),dp(12),dp(4));item.setFocusable(true);item.setClickable(true);
            item.setBackground(bg(0xFF17233A,12));
            item.setOnFocusChangeListener((view,hasFocus)->view.setBackground(bg(hasFocus?ACCENT:0xFF17233A,12)));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62));ip.bottomMargin=dp(8);list.addView(item,ip);if(chips!=null&&shown==0){for(int ci=0;ci<chips.getChildCount();ci++)chips.getChildAt(ci).setNextFocusDownId(item.getId());}if(chips!=null&&chips.getChildCount()>0)item.setNextFocusUpId(chips.getChildAt(0).getId());
            ImageView artwork=new ImageView(this);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);item.addView(artwork,new LinearLayout.LayoutParams(dp(76),dp(52)));if(c.logo!=null&&!c.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(c.logo).override(dp(76),dp(52)).centerCrop().dontAnimate().placeholder(bg(CARD,8)).error(bg(CARD,8)).into(artwork);else artwork.setImageDrawable(bg(CARD,8));
            LinearLayout meta=column();meta.setPadding(dp(12),0,0,0);item.addView(meta,new LinearLayout.LayoutParams(0,-1,1));meta.addView(text(itemName,14,Color.WHITE,true));String entryType=c.type==null?"":c.type;String programmeNow=findEpgProgram(epgNow,c),programmeNext=findEpgProgram(epgNext,c);String detail=itemGroup+"  •  "+(entryType.equals("live")||entryType.equals("m3u")?"En direct":entryType.equals("series")?"Série":"VOD");if(!programmeNow.isEmpty())detail+="  •  En cours : "+programmeNow;if(!programmeNext.isEmpty())detail+="  •  Ensuite : "+programmeNext;if(section.equals("HISTORIQUE")){long pos=historyPosition(c);detail+="  •  Reprendre à "+(pos/60000)+" min";}meta.addView(text(detail,10,MUTED,false));
            TextView fav=text(favorites.contains(c.url)?"♥":"♡",22,0xFFFF7199,true);fav.setGravity(Gravity.CENTER);fav.setId(View.generateViewId());fav.setFocusable(true);fav.setClickable(true);fav.setBackground(bg(CARD,8));fav.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?ACCENT:CARD,8)));item.setNextFocusRightId(fav.getId());item.addView(fav,new LinearLayout.LayoutParams(dp(52),-1));fav.setNextFocusLeftId(item.getId());fav.setOnClickListener(v->{if(!favorites.add(c.url))favorites.remove(c.url);getPreferences(0).edit().putStringSet(profileFavoritesKey(),new HashSet<>(favorites)).apply();fav.setText(favorites.contains(c.url)?"♥":"♡");fav.setContentDescription(favorites.contains(c.url)?"Retirer des favoris":"Ajouter aux favoris");});fav.setOnLongClickListener(v->{showAssignFavoriteGroup(c);return true;});item.setOnClickListener(v->openContent(c));shown++;if(shown>=maxRenderItems)break;}
        if(shown>=maxRenderItems){
            TextView more=text("Afficher les chaînes / contenus suivants…",13,Color.WHITE,true);
            more.setGravity(Gravity.CENTER);more.setFocusable(true);more.setClickable(true);more.setTag("MTV_LOAD_MORE");more.setPadding(dp(12),dp(10),dp(12),dp(10));more.setBackground(bg(ACCENT,10));
            LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(-1,dp(48));moreParams.topMargin=dp(4);moreParams.bottomMargin=dp(12);list.addView(more,moreParams);
            more.setOnClickListener(v->{maxRenderItems=Math.min(MAX_CATALOG_ITEMS,maxRenderItems+40);renderList();if(list.getChildCount()>0&&!"MTV_LOAD_MORE".equals(list.getChildAt(list.getChildCount()-1).getTag()))list.getChildAt(list.getChildCount()-1).requestFocus();});
        }
        if(section.equals("ACCUEIL")&&(q.isEmpty()||q.trim().isEmpty())){renderHome();return;}if(shown==0){TextView empty=text(section.equals("MTV")?(mtvPlaylistUrl.isEmpty()?"Configure une playlist M3U autorisée pour MTV dans Paramètres.":"Aucune chaîne MTV disponible dans cette source."):(all.isEmpty()?"Aucun contenu chargé. Configure une source M3U ou Xtream dans Paramètres.":"Aucun résultat dans cette rubrique. Vérifie la source ou le filtre."),14,MUTED,false);empty.setPadding(dp(12),dp(30),dp(12),dp(20));list.addView(empty);}restoreCatalogFocus(restoreFocusUrl);}
    // Conserve l'élément focalisé lors d'une reconstruction du catalogue.
    private String focusedCatalogItemUrl(){
        View focused=getCurrentFocus();
        while(focused!=null){
            android.view.ViewParent parent=focused.getParent();
            if(parent==list){Object tag=focused.getTag();return tag instanceof String?(String)tag:"";}
            focused=parent instanceof View?(View)parent:null;
        }
        return "";
    }
    private void restoreCatalogFocus(String url){
        if(url==null||url.isEmpty()||list==null)return;
        for(int i=0;i<list.getChildCount();i++){
            View child=list.getChildAt(i);
            Object tag=child.getTag();
            if(url.equals(tag)){
                child.requestFocus();
                if(catalogScroll!=null){Rect rect=new Rect();child.getDrawingRect(rect);catalogScroll.requestChildRectangleOnScreen(child,rect,true);}
                return;
            }
        }
    }
    private void play(MediaEntry c){failedInCurrentRun.clear();transientRetryAttempts.clear();startPlayer(c);}
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
            if(activeFilter!=null&&!filterMatches(activeFilter,entry.group,entry.name))continue;
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
    private boolean matchSectionFor(MediaEntry entry){return matchSection(entry);}
    private boolean isAnimeEntry(MediaEntry e){
        String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);
        return v.contains("anime")||v.contains("animation")||v.contains("cartoon");
    }
    private boolean isMusicEntry(MediaEntry e){String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);String[] keys={"music","musique","radio","mtv","trace","mezzo","mcm","vevo","concert","hits","clip"};for(String k:keys)if(v.contains(k))return true;return false;}
    private boolean isNewsEntry(MediaEntry e){String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);String[] keys={"news","info","information","actualité","actualite","journal","france 24","bfm","cnews","lci","euronews","al jazeera","bbc","cnn"};for(String k:keys)if(v.contains(k))return true;return false;}
    // Diagnostic reseau sans journaliser l'URL complete (les URLs Xtream peuvent contenir des identifiants).
    private String safeHost(String address){try{String host=Uri.parse(address).getHost();return host==null?"unknown":host;}catch(Exception ignored){return "unknown";}}
    private boolean isTransientPlaybackError(int errorCode){
        return errorCode==2000||errorCode==2001||errorCode==2002;
    }
    private MediaItem buildCompatibleMediaItem(MediaEntry entry){
        Uri uri=Uri.parse(entry.url);
        String path=uri.getPath()==null?"":uri.getPath().toLowerCase(Locale.ROOT);
        String mime=null;
        if(path.endsWith(".m3u8"))mime=androidx.media3.common.MimeTypes.APPLICATION_M3U8;
        else if(path.endsWith(".mpd"))mime=androidx.media3.common.MimeTypes.APPLICATION_MPD;
        else if(path.endsWith(".ismc")||path.contains(".ism/manifest"))mime=androidx.media3.common.MimeTypes.APPLICATION_SS;
        MediaItem.Builder builder=new MediaItem.Builder().setUri(uri).setMediaId(entry.url);
        if(mime!=null)builder.setMimeType(mime);
        return builder.build();
    }
    private void startPlayer(MediaEntry c){
        if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(c)){
            Toast.makeText(this,"Ce contenu est masqué par le profil Enfant.",Toast.LENGTH_SHORT).show();return;
        }
        if(c==null||c.url==null||!(c.url.startsWith("https://")||c.url.startsWith("http://"))){
            Toast.makeText(this,"Adresse du flux invalide",Toast.LENGTH_LONG).show();return;
        }
        try{
            savePlaybackPosition();releasePlayer();currentEntry=c;
            androidx.media3.exoplayer.DefaultRenderersFactory renderersFactory=new androidx.media3.exoplayer.DefaultRenderersFactory(getApplicationContext()).setEnableDecoderFallback(true);
            player=new ExoPlayer.Builder(getApplicationContext(),renderersFactory).build();
            playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);playerView.setBackgroundColor(Color.BLACK);
            GestureDetector playerGestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(android.view.MotionEvent e){return true;}@Override public boolean onDoubleTap(android.view.MotionEvent e){togglePlayerExpanded();return true;}});playerView.setOnTouchListener((v,event)->{playerGestures.onTouchEvent(event);return false;});
            playerToolbar=column();playerToolbar.setGravity(Gravity.CENTER);playerToolbar.setPadding(0,dp(3),0,dp(3));
            TextView expand=text("⛶",23,Color.WHITE,true);
            expand.setGravity(Gravity.CENTER);expand.setContentDescription("Plein écran");expand.setFocusable(true);expand.setClickable(true);expand.setPadding(dp(5),dp(5),dp(5),dp(5));expand.setBackground(bg(CARD,10));
            expand.setOnFocusChangeListener((v,focus)->stylePlayerButton((TextView)v,focus));
            expand.setOnClickListener(v->togglePlayerExpanded());
            LinearLayout transportControls=row();transportControls.setGravity(Gravity.CENTER);transportControls.addView(playerControl("🔇","Muet / Son",()->{if(player!=null){boolean muted=player.getVolume()>0f;player.setVolume(muted?0f:1f);Toast.makeText(this,muted?"Son coupé":"Son activé",Toast.LENGTH_SHORT).show();}}),new LinearLayout.LayoutParams(dp(44),dp(40)));
            transportControls.addView(playerControl("⏮","Précédent",()->{if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();}),new LinearLayout.LayoutParams(dp(44),dp(40)));
            transportControls.addView(playerControl("▶","Pause / Lecture",()->{if(player!=null){if(player.isPlaying())player.pause();else player.play();}}),new LinearLayout.LayoutParams(dp(44),dp(40)));
            transportControls.addView(playerControl("⏭","Suivant",()->{if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();}),new LinearLayout.LayoutParams(dp(44),dp(40)));
            LinearLayout optionControls=row();optionControls.setGravity(Gravity.CENTER);optionControls.addView(playerControl("♫","Pistes audio",()->{if(player!=null)new androidx.media3.ui.TrackSelectionDialogBuilder(this,"Pistes audio",player,androidx.media3.common.C.TRACK_TYPE_AUDIO).build().show();}),new LinearLayout.LayoutParams(dp(44),dp(38)));optionControls.addView(playerControl("CC","Sous-titres",()->{if(player!=null)new androidx.media3.ui.TrackSelectionDialogBuilder(this,"Sous-titres",player,androidx.media3.common.C.TRACK_TYPE_TEXT).build().show();}),new LinearLayout.LayoutParams(dp(44),dp(38)));optionControls.addView(playerControl("▣","Format image",()->{if(playerView!=null){int[] modes={AspectRatioFrameLayout.RESIZE_MODE_ZOOM,AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_FILL,AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH};String[] labels={"Zoom","Ajuster","Remplir","Largeur fixe"};int current=0;for(int i=0;i<modes.length;i++)if(modes[i]==playerResizeMode)current=i;playerResizeMode=modes[(current+1)%modes.length];playerView.setResizeMode(playerResizeMode);Toast.makeText(this,"Format : "+labels[(current+1)%labels.length],Toast.LENGTH_SHORT).show();}}),new LinearLayout.LayoutParams(dp(44),dp(38)));transportControls.addView(expand,new LinearLayout.LayoutParams(-2,dp(40)));playerToolbar.addView(transportControls,new LinearLayout.LayoutParams(-1,dp(42)));playerToolbar.addView(optionControls,new LinearLayout.LayoutParams(-1,dp(38)));
            if(catalogScroll.getParent()==playerArea)playerArea.removeView(catalogScroll);
            boolean wideLayout=(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)>=720f;
            playerArea.setOrientation(wideLayout?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            LinearLayout videoColumn=column();videoColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            if(wideLayout)playerArea.addView(videoColumn,new LinearLayout.LayoutParams(0,-1,0.62f));
            else playerArea.addView(videoColumn,new LinearLayout.LayoutParams(-1,dp(327)));
            if(wideLayout)videoColumn.addView(playerView,new LinearLayout.LayoutParams(-1,0,1f));
            else videoColumn.addView(playerView,new LinearLayout.LayoutParams(-1,dp(245)));
            videoColumn.addView(playerToolbar,new LinearLayout.LayoutParams(-1,dp(82)));
            if(wideLayout)playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(0,-1,0.38f));
            else playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,0,1f));
            final ExoPlayer activePlayer=player;
            lastPlayedUrl=c.url;long resumePosition=("live".equals(c.type)||"m3u".equals(c.type))?0L:historyPosition(c);pendingResumePosition=0L;
            recordHistoryEntry(c);
            List<MediaEntry> queue=playableQueue(c);ArrayList<MediaItem> mediaItems=new ArrayList<>();int startIndex=0;
            for(int i=0;i<queue.size();i++){
                MediaEntry entry=queue.get(i);
                mediaItems.add(buildCompatibleMediaItem(entry));
                if(entry.url.equals(c.url))startIndex=i;
            }
            activePlayer.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_READY){MediaEntry playing=currentEntry;String readyUrl=playing==null?c.url:playing.url;transientRetryAttempts.remove(readyUrl);recordSuccessfulStream(readyUrl);}
                    else if(state==Player.STATE_ENDED){clearPlaybackPosition(currentEntry);}
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    MediaEntry diagnosticEntry=currentEntry;
                    String diagnosticAddress=diagnosticEntry!=null?diagnosticEntry.url:c.url;
                    PlaybackDiagnostics.record(MainActivity.this,e.errorCode,safeHost(diagnosticAddress),e.getClass().getSimpleName());
                    android.util.Log.w("MTv-Player","Playback error code="+e.errorCode+" host="+safeHost(diagnosticAddress)+" type="+e.getClass().getSimpleName());
                    postUi(()->{
                        if(player!=activePlayer||destroyed)return;
                        int failedIndex=activePlayer.getCurrentMediaItemIndex();
                        String failedUrl=(failedIndex>=0&&failedIndex<queue.size())?queue.get(failedIndex).url:(currentEntry==null?c.url:currentEntry.url);
                        if(failedUrl==null)failedUrl=c.url;
                        int retryAttempt=transientRetryAttempts.containsKey(failedUrl)?transientRetryAttempts.get(failedUrl):0;
                        if(isTransientPlaybackError(e.errorCode)&&retryAttempt<2){
                            retryAttempt++;
                            transientRetryAttempts.put(failedUrl,retryAttempt);
                            final int expectedIndex=failedIndex;
                            final String expectedUrl=failedUrl;
                            long retryDelay=retryAttempt==1?1200L:2800L;
                            if(pendingPlaybackRetry!=null)mainHandler.removeCallbacks(pendingPlaybackRetry);
                            pendingPlaybackRetry=()->{
                                pendingPlaybackRetry=null;
                                if(player!=activePlayer||destroyed)return;
                                int currentIndex=activePlayer.getCurrentMediaItemIndex();
                                if(currentIndex!=expectedIndex||currentIndex<0||currentIndex>=queue.size())return;
                                MediaEntry current=queue.get(currentIndex);
                                if(current==null||!expectedUrl.equals(current.url))return;
                                activePlayer.prepare();
                                activePlayer.play();
                            };
                            mainHandler.postDelayed(pendingPlaybackRetry,retryDelay);
                            return;
                        }
                        transientRetryAttempts.remove(failedUrl);
                        failedInCurrentRun.add(failedUrl);
                        if(failedIndex<0||failedIndex>=queue.size()){
                            failedIndex=-1;
                            for(int i=0;i<queue.size();i++)if(failedUrl.equals(queue.get(i).url)){failedIndex=i;break;}
                        }
                        int nextIndex=-1;
                        for(int i=Math.max(0,failedIndex+1);i<queue.size();i++){
                            MediaEntry candidate=queue.get(i);
                            if(candidate!=null&&candidate.url!=null&&!failedInCurrentRun.contains(candidate.url)){nextIndex=i;break;}
                        }
                        recordFailedStream(failedUrl);
                        if(nextIndex>=0){
                            activePlayer.seekTo(nextIndex,0L);activePlayer.prepare();activePlayer.play();
                        }else{
                            activePlayer.stop();
                            Toast.makeText(MainActivity.this,"Aucun autre flux disponible dans cette rubrique.",Toast.LENGTH_LONG).show();
                        }
                    });
                }
                @Override public void onMediaItemTransition(MediaItem item,int reason){
                    if(item==null||item.localConfiguration==null||item.localConfiguration.uri==null)return;
                    String uri=item.localConfiguration.uri.toString();
                    transientRetryAttempts.remove(uri);
                    for(MediaEntry entry:queue)if(entry!=null&&entry.url!=null&&entry.url.equals(uri)){
                        currentEntry=entry;lastPlayedUrl=entry.url;
                        recordHistoryEntry(entry);
                        break;
                    }
                }
            });
            activePlayer.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
            activePlayer.setMediaItems(mediaItems,startIndex,resumePosition);activePlayer.prepare();activePlayer.play();
            playbackSaveRunnable=()->{if(player!=activePlayer||destroyed)return;savePlaybackPosition();mainHandler.postDelayed(playbackSaveRunnable,15000L);};
            mainHandler.postDelayed(playbackSaveRunnable,15000L);
            status.setText("Lecture : "+c.name);
        }catch(Exception e){PlaybackDiagnostics.record(this,-1,safeHost(c.url),e.getClass().getSimpleName());android.util.Log.w("MTv-Player","Player initialization failed host="+safeHost(c.url)+" type="+e.getClass().getSimpleName());releasePlayer();Toast.makeText(this,"Impossible de lancer ce flux",Toast.LENGTH_LONG).show();}
    }

    private void styleFullscreenPlayerControls(){
        if(playerView==null)return;
        styleFullscreenControlTree(playerView);
    }
    private void styleFullscreenControlTree(View view){
        if(view==null)return;
        if(view!=playerView&&view.isFocusable()){
            view.setOnFocusChangeListener((v,focused)->{
                if(focused){GradientDrawable d=bg(0xFFFFC247,9);d.setStroke(dp(3),0xFFFF7900);v.setBackground(d);v.setElevation(dp(7));}
                else{v.setBackgroundColor(Color.TRANSPARENT);v.setElevation(0f);}
            });
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleFullscreenControlTree(group.getChildAt(i));}
    }
    private void togglePlayerExpanded(){
        if(playerView==null||playerArea==null)return;
        playerFullscreen=!playerFullscreen;playerExpanded=playerFullscreen;
        // Masquer le conteneur complet de la barre laterale (pas seulement ses libelles).
        sidebarShell.setVisibility(playerFullscreen?View.GONE:View.VISIBLE);
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
        View videoColumn=playerView.getParent() instanceof ViewGroup? (View)playerView.getParent():null;
        boolean wideLayout=(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)>=720f;
        // En plein écran, la vidéo occupe toute la largeur, y compris sur TV.
        // L'ancien width=0 avec weight=0 réduisait la vue à zéro sur écran large.
        playerArea.setOrientation(playerFullscreen?LinearLayout.VERTICAL:(wideLayout?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL));
        if(videoColumn instanceof LinearLayout){
            LinearLayout.LayoutParams columnParams=(LinearLayout.LayoutParams)videoColumn.getLayoutParams();
            columnParams.width=playerFullscreen?-1:(wideLayout?0:-1);columnParams.height=playerFullscreen?-1:(wideLayout?-1:dp(327));
            columnParams.weight=playerFullscreen?0f:(wideLayout?0.62f:0f);videoColumn.setLayoutParams(columnParams);
        }
        LinearLayout.LayoutParams videoParams=(LinearLayout.LayoutParams)playerView.getLayoutParams();
        videoParams.width=-1;videoParams.height=playerFullscreen?-1:(wideLayout?0:dp(245));
        videoParams.weight=playerFullscreen?0f:(wideLayout?1f:0f);playerView.setLayoutParams(videoParams);
        if(!playerFullscreen&&catalogScroll.getParent()!=playerArea){
            if(wideLayout)playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(0,-1,0.38f));
            else playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,0,1f));
        }
        applyImmersiveMode();styleFullscreenPlayerControls();playerView.post(()->{styleFullscreenPlayerControls();playerView.requestFocus();});
    }
    private void applyImmersiveMode(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showMediaDetailDialog(MediaEntry entry,String plot,String genre,String year,String duration,String rating,String director,String cast,String actionLabel,Runnable action){
        if(entry==null||destroyed)return;
        final AlertDialog[] dialogRef=new AlertDialog[1];
        LinearLayout panel=column();panel.setPadding(dp(18),dp(14),dp(18),dp(12));panel.setBackgroundColor(BG);
        LinearLayout hero=row();hero.setGravity(Gravity.TOP);panel.addView(hero,new LinearLayout.LayoutParams(-1,-2));
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackground(bg(CARD,10));
        LinearLayout.LayoutParams posterParams=new LinearLayout.LayoutParams(dp(126),dp(178));posterParams.rightMargin=dp(15);hero.addView(poster,posterParams);
        if(entry.logo!=null&&!entry.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(entry.logo).override(dp(126),dp(178)).centerCrop().dontAnimate().placeholder(bg(CARD,10)).error(bg(CARD,10)).into(poster);
        LinearLayout summary=column();summary.setGravity(Gravity.CENTER_VERTICAL);hero.addView(summary,new LinearLayout.LayoutParams(0,-2,1f));
        TextView typeLabel=text("series".equals(entry.type)?"SÉRIE":"movie".equals(entry.type)?"FILM":"CONTENU",10,FOCUS_GOLD,true);summary.addView(typeLabel,new LinearLayout.LayoutParams(-1,dp(22)));
        TextView title=text(entry.name==null?"Contenu":entry.name,19,Color.WHITE,true);title.setMaxLines(3);summary.addView(title,new LinearLayout.LayoutParams(-1,-2));
        String meta="";if(year!=null&&!year.isEmpty())meta+=year;if(duration!=null&&!duration.isEmpty())meta+=(meta.isEmpty()?"":"  •  ")+duration;if(rating!=null&&!rating.isEmpty())meta+=(meta.isEmpty()?"":"  •  ")+"★ "+rating;
        if(!meta.isEmpty()){TextView metaView=text(meta,11,0xFFFFD16B,true);metaView.setPadding(0,dp(8),0,0);summary.addView(metaView,new LinearLayout.LayoutParams(-1,-2));}
        if(genre!=null&&!genre.isEmpty()){TextView genreView=text(genre,11,0xFFB8C8DE,false);genreView.setMaxLines(2);genreView.setPadding(0,dp(6),0,0);summary.addView(genreView,new LinearLayout.LayoutParams(-1,-2));}
        if(plot!=null&&!plot.trim().isEmpty()){TextView synopsisTitle=text("SYNOPSIS",11,FOCUS_GOLD,true);synopsisTitle.setPadding(0,dp(16),0,dp(5));panel.addView(synopsisTitle);TextView synopsis=text(plot.trim(),12,0xFFE1E7F0,false);synopsis.setLineSpacing(dp(2),1f);panel.addView(synopsis,new LinearLayout.LayoutParams(-1,-2));}
        if((director!=null&&!director.isEmpty())||(cast!=null&&!cast.isEmpty())){TextView credits=text((director==null||director.isEmpty()?"":"Réalisation : "+director)+(cast==null||cast.isEmpty()?"":"\nAvec : "+cast),10,MUTED,false);credits.setPadding(0,dp(10),0,0);panel.addView(credits,new LinearLayout.LayoutParams(-1,-2));}
        ArrayList<MediaEntry> recommendations=new ArrayList<>();String targetType=entry.type==null?"":entry.type;String targetGroup=entry.group==null?"":entry.group.trim();
        if(!targetGroup.isEmpty())for(MediaEntry candidate:all){
            if(candidate==null||candidate==entry||candidate.url==null||candidate.url.isEmpty()||candidate.url.equals(entry.url))continue;
            if(!targetType.equals(candidate.type)||(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(candidate)))continue;
            if(candidate.group==null||!targetGroup.equalsIgnoreCase(candidate.group.trim()))continue;
            boolean duplicate=false;for(MediaEntry picked:recommendations)if(picked.url.equals(candidate.url)){duplicate=true;break;}
            if(!duplicate)recommendations.add(candidate);if(recommendations.size()>=6)break;
        }
        if(!recommendations.isEmpty()){
            TextView recommendationTitle=text("À DÉCOUVRIR AUSSI",11,FOCUS_GOLD,true);recommendationTitle.setPadding(0,dp(18),0,dp(8));panel.addView(recommendationTitle);
            HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.setHorizontalScrollBarEnabled(false);horizontal.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout cards=row();horizontal.addView(cards);panel.addView(horizontal,new LinearLayout.LayoutParams(-1,dp(154)));
            for(MediaEntry candidate:recommendations){
                LinearLayout card=column();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(dp(6),dp(6),dp(6),dp(5));card.setBackground(bg(PANEL,9));card.setFocusable(true);card.setClickable(true);
                LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(dp(112),dp(146));cardParams.rightMargin=dp(8);cards.addView(card,cardParams);
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(bg(CARD,7));card.addView(image,new LinearLayout.LayoutParams(dp(98),dp(102)));
                if(candidate.logo!=null&&!candidate.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(candidate.logo).placeholder(bg(CARD,7)).error(bg(CARD,7)).into(image);
                TextView candidateName=text(candidate.name==null?"Contenu":candidate.name,10,Color.WHITE,true);candidateName.setGravity(Gravity.CENTER);candidateName.setMaxLines(2);candidateName.setPadding(0,dp(5),0,0);card.addView(candidateName,new LinearLayout.LayoutParams(-1,-2));
                card.setOnFocusChangeListener((v,focused)->{GradientDrawable d=bg(focused?ACCENT:PANEL,9);if(focused)d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);});
                card.setOnClickListener(v->{if(dialogRef[0]!=null)dialogRef[0].dismiss();openContent(candidate);});
            }
        }
        LinearLayout actions=row();actions.setGravity(Gravity.CENTER_VERTICAL);actions.setPadding(0,dp(14),0,0);panel.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView primary=text(actionLabel,13,Color.BLACK,true);primary.setGravity(Gravity.CENTER);primary.setBackground(bg(0xFFFFC247,9));primary.setFocusable(true);primary.setClickable(true);actions.addView(primary,new LinearLayout.LayoutParams(0,-1,1f));
        TextView close=text("Fermer",12,Color.WHITE,true);close.setGravity(Gravity.CENTER);close.setBackground(bg(CARD,9));close.setFocusable(true);close.setClickable(true);LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(dp(100),-1);closeParams.leftMargin=dp(9);actions.addView(close,closeParams);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();dialogRef[0]=dialog;
        primary.setOnFocusChangeListener((v,focused)->{GradientDrawable d=bg(focused?0xFFFFD36A:0xFFFFC247,9);if(focused)d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);});
        close.setOnFocusChangeListener((v,focused)->{GradientDrawable d=bg(focused?ACCENT:CARD,9);if(focused)d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);});
        primary.setOnClickListener(v->{dialog.dismiss();if(action!=null)action.run();});close.setOnClickListener(v->dialog.dismiss());
        dialog.setOnShowListener(d->{if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.88f),-2);}primary.requestFocus();});
        dialog.show();
    }
    private void showSeasonPicker(MediaEntry series,TreeMap<Integer,ArrayList<MediaEntry>> seasons){
        if(seasons==null||seasons.isEmpty())return;ArrayList<Integer> seasonKeys=new ArrayList<>(seasons.keySet());String[] seasonNames=new String[seasonKeys.size()];
        for(int i=0;i<seasonKeys.size();i++)seasonNames[i]=seasonKeys.get(i)>0?"Saison "+seasonKeys.get(i):"Autres épisodes";
        new AlertDialog.Builder(this).setTitle(series.name).setItems(seasonNames,(dialog,which)->{
            ArrayList<MediaEntry> episodes=seasons.get(seasonKeys.get(which));if(episodes==null)return;String[] labels=new String[episodes.size()];
            for(int i=0;i<episodes.size();i++){MediaEntry episode=episodes.get(i);long position=historyPosition(episode);labels[i]="Épisode "+episode.episodeNumber+" — "+episode.name+(position>0L?"  •  Reprendre à "+(position/60000)+" min":"");}
            new AlertDialog.Builder(this).setTitle(seasonNames[which]).setItems(labels,(d,index)->play(episodes.get(index))).setNegativeButton("Retour",null).show();
        }).setNegativeButton("Fermer",null).show();
    }
    private void showSeries(MediaEntry s){
        if(!"XTREAM".equals(sourceMode)||s==null||s.id==null||s.id.isEmpty()){Toast.makeText(this,"Les saisons et épisodes nécessitent une source Xtream compatible.",Toast.LENGTH_LONG).show();return;}
        executeIo(()->{
            String err=null;final TreeMap<Integer,ArrayList<MediaEntry>> seasons=new TreeMap<>();
            try{
                JSONObject data=new JSONObject(get(api("get_series_info","&series_id="+enc(s.id))));JSONObject info=data.optJSONObject("info");
                if(info!=null){s.plot=info.optString("plot",s.plot);s.genre=info.optString("genre",s.genre);s.cast=info.optString("cast",s.cast);s.director=info.optString("director",s.director);s.rating=info.optString("rating",s.rating);s.year=info.optString("releaseDate",s.year);String cover=info.optString("cover","");if((s.logo==null||s.logo.isEmpty())&&!cover.isEmpty())s.logo=cover;}
                JSONObject episodes=data.optJSONObject("episodes");
                if(episodes!=null){Iterator<String> keys=episodes.keys();while(keys.hasNext()){
                    String key=keys.next();int seasonNo;try{seasonNo=Integer.parseInt(key);}catch(Exception ignored){seasonNo=0;}JSONArray arr=episodes.optJSONArray(key);if(arr==null)continue;
                    ArrayList<MediaEntry> bucket=seasons.get(seasonNo);if(bucket==null){bucket=new ArrayList<>();seasons.put(seasonNo,bucket);}
                    for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");if(id.isEmpty())continue;String ext=o.optString("container_extension","mp4");String url=xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"series/"+enc(xtreamUser)+"/"+enc(xtreamPass)+"/"+id+"."+ext;MediaEntry ep=new MediaEntry(o.optString("title","Épisode "+(i+1)),url,"Saison "+(seasonNo>0?seasonNo:key),"","episode");ep.episodeNumber=o.optInt("episode_num",i+1);ep.seasonNumber=seasonNo;ep.seriesTitle=s.name==null?"":s.name;ep.duration=o.optString("info","");bucket.add(ep);}
                }}
                for(ArrayList<MediaEntry> bucket:seasons.values())Collections.sort(bucket,(a,b)->Integer.compare(a.episodeNumber,b.episodeNumber));
            }catch(Exception e){err=e.getMessage();}
            final String error=err;postUi(()->{if(destroyed)return;if(error!=null||seasons.isEmpty()){Toast.makeText(this,"Impossible de charger les épisodes de cette série.",Toast.LENGTH_LONG).show();return;}showMediaDetailDialog(s,s.plot,s.genre,s.year,"",s.rating,s.director,s.cast,"Voir les saisons",()->showSeasonPicker(s,seasons));});
        });
    }
    private void showMovieDetails(MediaEntry movie){
        if(movie==null)return;if(!"XTREAM".equals(sourceMode)||movie.id==null||movie.id.isEmpty()){play(movie);return;}
        executeIo(()->{
            String plot=movie.plot,genre=movie.genre,cast=movie.cast,director=movie.director,rating=movie.rating,year=movie.year,duration=movie.duration;
            try{JSONObject root=new JSONObject(get(api("get_vod_info","&vod_id="+enc(movie.id))));JSONObject info=root.optJSONObject("info");if(info!=null){plot=info.optString("plot",plot);genre=info.optString("genre",genre);cast=info.optString("cast",cast);director=info.optString("director",director);rating=info.optString("rating",rating);year=info.optString("releasedate",info.optString("releaseDate",year));duration=info.optString("duration",duration);String image=info.optString("movie_image","");if((movie.logo==null||movie.logo.isEmpty())&&!image.isEmpty())movie.logo=image;}}catch(Exception ignored){}
            final String fPlot=plot,fGenre=genre,fCast=cast,fDirector=director,fRating=rating,fYear=year,fDuration=duration;
            postUi(()->{if(destroyed)return;showMediaDetailDialog(movie,fPlot,fGenre,fYear,fDuration,fRating,fDirector,fCast,"Lire le film",()->play(movie));});
        });
    }
    private String buildMediaDetails(MediaEntry entry,String plot,String genre,String year,String duration,String rating,String director,String cast){StringBuilder b=new StringBuilder();if(genre!=null&&!genre.isEmpty())b.append(genre).append("\n");if(year!=null&&!year.isEmpty())b.append(year).append("  •  ");if(duration!=null&&!duration.isEmpty())b.append(duration).append("  •  ");if(rating!=null&&!rating.isEmpty())b.append("Note ").append(rating);if(director!=null&&!director.isEmpty())b.append("\nRéalisation : ").append(director);if(cast!=null&&!cast.isEmpty())b.append("\nAvec : ").append(cast);if(plot!=null&&!plot.isEmpty())b.append("\n\n").append(plot);if(b.length()==0)b.append(entry.group==null?"Informations non fournies par la source.":entry.group+"\nInformations détaillées non fournies par la source.");return b.toString();}
    private void openContent(MediaEntry entry){if(entry==null)return;if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(entry)){Toast.makeText(this,"Ce contenu est masqué par le profil Enfant.",Toast.LENGTH_SHORT).show();return;}if("series".equals(entry.type))showSeries(entry);else if("movie".equals(entry.type))showMovieDetails(entry);else play(entry);}

    private boolean isAdultEntry(MediaEntry item){
        if(item==null)return false;
        StringBuilder metadata=new StringBuilder(256);
        if(item.name!=null)metadata.append(item.name).append(' ');
        if(item.group!=null)metadata.append(item.group).append(' ');
        if(item.plot!=null)metadata.append(item.plot).append(' ');
        if(item.genre!=null)metadata.append(item.genre).append(' ');
        if(item.cast!=null)metadata.append(item.cast).append(' ');
        if(item.director!=null)metadata.append(item.director).append(' ');
        if(item.seriesTitle!=null)metadata.append(item.seriesTitle);
        String value=metadata.toString().toLowerCase(Locale.ROOT);
        String[] blocked={"adult","xxx","18+","18 plus","porn","erotic","adulte","hentai","onlyfans","explicit","nude"};
        for(String word:blocked)if(value.contains(word))return true;
        return false;
    }
    private String profileFavoritesKey(){return PROFILE_CHILD.equals(activeProfile)?"favorites_child":"favorites";}
    private String profileFavoriteGroupsKey(){return PROFILE_CHILD.equals(activeProfile)?"favorite_groups_child":"favorite_groups";}
    private String profileFavoriteGroupKey(String group){return (PROFILE_CHILD.equals(activeProfile)?"favorite_group_child_":"favorite_group_")+key(group);}
    private void loadProfileFavoriteData(){
        favorites.clear();favoriteGroups.clear();
        Set<String> savedFavorites=prefs.getStringSet(profileFavoritesKey(),new HashSet<>());
        if(savedFavorites!=null)favorites.addAll(savedFavorites);
        Set<String> savedGroups=prefs.getStringSet(profileFavoriteGroupsKey(),new HashSet<>());
        if(savedGroups!=null)for(String group:savedGroups){
            Set<String> urls=prefs.getStringSet(profileFavoriteGroupKey(group),new HashSet<>());
            favoriteGroups.put(group,new LinkedHashSet<>(urls==null?new HashSet<>():urls));
        }
    }
    private void switchProfile(String next){
        if(next==null||next.equals(activeProfile))return;
        if(PROFILE_CHILD.equals(next)&&prefs.getString("parental_pin_hash","").isEmpty()){
            showPinSetupForChild();
            return;
        }
        activeProfile=next;
        prefs.edit().putString("active_profile",next).apply();
        loadProfileFavoriteData();
        activeFilter="Tout";
        updateChips();
        renderList();
        if(PROFILE_CHILD.equals(next))Toast.makeText(this,"Profil Enfant activé • Filtrage indicatif",Toast.LENGTH_LONG).show();
    }
    private void showPinSetupForChild(){
        EditText pin=field("Nouveau code PIN (4 à 8 chiffres)");
        EditText confirm=field("Confirmer le code PIN");
        pin.setInputType(2);confirm.setInputType(2);
        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20),dp(8),dp(20),0);
        form.addView(pin,new LinearLayout.LayoutParams(-1,dp(52)));
        form.addView(confirm,new LinearLayout.LayoutParams(-1,dp(52)));
        new AlertDialog.Builder(this).setTitle("Protéger le profil Enfant")
            .setMessage("Crée un code parental pour pouvoir revenir au profil Adulte.")
            .setView(form).setNegativeButton("Annuler",null)
            .setPositiveButton("Enregistrer",(d,w)->{
                String value=pin.getText().toString();
                if(!value.matches("\\d{4,8}")){
                    Toast.makeText(this,"Utilise un code de 4 à 8 chiffres",Toast.LENGTH_LONG).show();
                }else if(!value.equals(confirm.getText().toString())){
                    Toast.makeText(this,"Les deux codes PIN ne correspondent pas",Toast.LENGTH_LONG).show();
                }else{
                    prefs.edit().putString("parental_pin_hash",hash(value)).apply();
                    switchProfile(PROFILE_CHILD);
                }
            }).show();
    }
    private void showProfileDialog(){
        String[] profiles={"Adulte","Enfant"};
        int selected=PROFILE_CHILD.equals(activeProfile)?1:0;
        new AlertDialog.Builder(this).setTitle("Profil GLOBAL TV")
            .setSingleChoiceItems(profiles,selected,(dialog,which)->{
                String next=profiles[which];
                if(PROFILE_CHILD.equals(next)){
                    dialog.dismiss();
                    switchProfile(next);
                    return;
                }else if(PROFILE_CHILD.equals(activeProfile)){
                    String pinHash=prefs.getString("parental_pin_hash","");
                    if(pinHash.isEmpty()){
                        Toast.makeText(this,"Code PIN parental manquant : configure-le depuis le profil Adulte.",Toast.LENGTH_LONG).show();
                    }else{
                        EditText pin=field("Code PIN parental"); pin.setInputType(2);
                        new AlertDialog.Builder(this).setTitle("Accès profil Adulte").setView(pin)
                            .setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{
                                if(pinHash.equals(hash(pin.getText().toString()))){
                                    switchProfile(PROFILE_ADULT);
                                }else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
                            }).show();
                    }
                }else{
                    switchProfile(PROFILE_ADULT);
                }
                dialog.dismiss();
            }).setNegativeButton("Fermer",null).show();
    }
    private boolean isWebSection(String target){return "MTV".equals(target)||"WITV".equals(target)||"FRSTV".equals(target)||"FSLIVE".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target)||"BRAFLIX".equals(target);}
    private String webUrlFor(String target){if("WITV".equals(target))return "https://wiflix.mov/";if("FRSTV".equals(target))return "https://french-stream.net/";if("FSLIVE".equals(target))return "https://fstv.one/";if("EPI XTV".equals(target))return "https://www.xnxx.com/";if("DUDETV".equals(target))return "https://theporndude.com/";if("BRAFLIX".equals(target))return "https://braflix.uk/";return "https://oxax.tv/";}
    private boolean isAllowedWebNavigation(String targetUrl){
        try{
            Uri target=Uri.parse(targetUrl),current=Uri.parse(activeWebUrl);
            String scheme=target.getScheme(),host=target.getHost(),currentHost=current.getHost();
            if(host==null||currentHost==null||!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)))return false;
            host=host.toLowerCase(Locale.ROOT);currentHost=currentHost.toLowerCase(Locale.ROOT);
            return host.equals(currentHost)||host.endsWith("."+currentHost)||currentHost.endsWith("."+host);
        }catch(Exception ignored){return false;}
    }
    private boolean isBlockedAdRequest(String requestUrl){
        try{
            Uri uri=Uri.parse(requestUrl);String host=uri.getHost();
            if(host==null)return false;host=host.toLowerCase(Locale.ROOT);
            String[] blocked={
                "doubleclick.net","googlesyndication.com","googleadservices.com","googletagservices.com",
                "adservice.google.com","popads.net","popcash.net","propellerads.com","exoclick.com",
                "trafficjunky.net","adsterra.com","taboola.com","outbrain.com","criteo.com",
                "smartadserver.com","adroll.com","mgid.com","adnxs.com","bidvertiser.com",
                "hilltopads.com"," monetag.com","onclicka.com","juicyads.com"
            };
            for(String domain:blocked){String clean=domain.trim();if(host.equals(clean)||host.endsWith("."+clean))return true;}
        }catch(Exception ignored){}
        return false;
    }
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
            settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
            settings.setLoadWithOverviewMode(true);settings.setUseWideViewPort(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            mtvWebView.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){
                    if(request==null||!request.isForMainFrame())return false;
                    String target=request.getUrl()==null?"":request.getUrl().toString();
                    if(!isAllowedWebNavigation(target)){Toast.makeText(MainActivity.this,"Redirection externe bloquée.",Toast.LENGTH_SHORT).show();return true;}
                    activeWebUrl=target;return false;
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,String target){
                    if(!isAllowedWebNavigation(target)){Toast.makeText(MainActivity.this,"Redirection externe bloquée.",Toast.LENGTH_SHORT).show();return true;}
                    activeWebUrl=target;return false;
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,android.webkit.WebResourceRequest request){
                    String target=request!=null&&request.getUrl()!=null?request.getUrl().toString():"";
                    if(isBlockedAdRequest(target))return new android.webkit.WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                    return super.shouldInterceptRequest(view,request);
                }
                @Override public void onPageFinished(WebView view,String loaded){super.onPageFinished(view,loaded);if(isWebSection(section)&&view.getVisibility()==View.VISIBLE)view.requestFocus();}
                @Override public void onReceivedError(WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceError error){super.onReceivedError(view,request,error);if(request.isForMainFrame())postUi(()->Toast.makeText(MainActivity.this,"Erreur de chargement de la page Web.",Toast.LENGTH_LONG).show());}
                @Override public void onReceivedHttpError(WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceResponse response){super.onReceivedHttpError(view,request,response);if(request.isForMainFrame()&&response.getStatusCode()>=400)postUi(()->Toast.makeText(MainActivity.this,"Erreur Web HTTP "+response.getStatusCode(),Toast.LENGTH_LONG).show());}
                @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){
                    if(view==mtvWebView){String reload=activeWebUrl;if(webContainer!=null&&webContainer.getParent()==content)content.removeView(webContainer);mtvWebView=null;webContainer=null;view.destroy();postUi(()->{Toast.makeText(MainActivity.this,"Le lecteur Web a redémarré.",Toast.LENGTH_LONG).show();if(isWebSection(section)&&reload!=null&&!reload.isEmpty())showSiteWebView(reload);});}
                    return true;
                }
            });
            mtvWebView.setWebChromeClient(new WebChromeClient(){
                @Override public void onShowCustomView(View view,CustomViewCallback callback){showWebCustomView(view,callback);}
                @Override public void onHideCustomView(){hideWebCustomView();}
                @Override public void onShowCustomView(View view,int requestedOrientation,CustomViewCallback callback){showWebCustomView(view,callback);}
                @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){return false;}
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
        if(webCustomView!=null){if(callback!=null)callback.onCustomViewHidden();return;}
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
        chipScroll.setVisibility(View.VISIBLE);status.setVisibility(View.GONE);catalogScroll.setVisibility(View.VISIBLE);if(playerArea!=null)playerArea.setVisibility(View.VISIBLE);
    }
    private void showMtvPlaylistDialog(){if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showMtvPlaylistDialog());return;}
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
        final int request=++mtvPlaylistRequestId;
        status.setText("Chargement de la source MTV…");
        executeIo(()->{
            ArrayList<MediaEntry> parsed=new ArrayList<>();String error=null;HttpURLConnection connection=null;
            try{
                connection=open(url);
                int code=connection.getResponseCode();
                if(code<200||code>=300)throw new IOException("Serveur HTTP "+code);
                InputStream input=connection.getInputStream();
                if(input==null)throw new IOException("Réponse vide du serveur");
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"))){
                    String line,name=null,group="MTV",logo="",tvgId="";int count=0;long bytes=0L;
                    while((line=readBoundedPlaylistLine(reader))!=null&&count<MAX_CATALOG_ITEMS){
                        if(request!=mtvPlaylistRequestId||destroyed)return;
                        bytes+=line.length()*2L;
                        if(bytes>15L*1024L*1024L)throw new IOException("Playlist trop volumineuse (limite 15 Mo)");
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
                }
            }catch(Exception ex){if(request==mtvPlaylistRequestId&&!destroyed)error=ex.getMessage();}
            finally{if(connection!=null)connection.disconnect();}
            if(request!=mtvPlaylistRequestId||destroyed)return;
            final String err=error;
            postUi(()->{
                if(request!=mtvPlaylistRequestId)return;
                if(err!=null){status.setText("Erreur source MTV : "+err);return;}
                mtvItems.clear();mtvItems.addAll(parsed);
                status.setText(parsed.size()+" éléments chargés • Source MTV dédiée");
                if(section.equals("MTV"))renderList();
            });
        });
    }
    private boolean consumeAdultSettingsAccess(){
        if(!PROFILE_CHILD.equals(activeProfile))return true;
        if(parentalSettingsAccessGranted){parentalSettingsAccessGranted=false;return true;}
        return false;
    }
    private void runAdultSettingsAction(Runnable action){
        if(!PROFILE_CHILD.equals(activeProfile)){action.run();return;}
        String expected=prefs.getString("parental_pin_hash","");
        if(expected.isEmpty()){
            Toast.makeText(this,"Action indisponible : configure le code parental depuis le profil Adulte.",Toast.LENGTH_LONG).show();
            return;
        }
        EditText pin=field("Code PIN parental");
        pin.setInputType(2);
        new AlertDialog.Builder(this).setTitle("Accès aux paramètres sensibles")
            .setMessage("Entre le code PIN pour modifier les sources ou les réglages.")
            .setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{
                if(expected.equals(hash(pin.getText().toString()))){
                    parentalSettingsAccessGranted=true;
                    action.run();
                }else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
            }).show();
    }
    private void showClearHistoryDialog(){
        if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showClearHistoryDialog());return;}
        new AlertDialog.Builder(this).setTitle("Effacer l’historique ?")
            .setMessage("Les positions de lecture et l’historique seront supprimés.")
            .setNegativeButton("Annuler",null).setPositiveButton("Effacer",(a,b)->{
                Map<String,?> allPrefs=prefs.getAll();SharedPreferences.Editor ed=prefs.edit();
                for(String k:allPrefs.keySet())if(k.startsWith("history_")||k.startsWith("position_"))ed.remove(k);
                ed.apply();renderList();
            }).show();
    }
    private void showSettings(){String[] options={"Changer de profil (Adulte / Enfant)","Ajouter une playlist M3U","Gérer les playlists M3U","Connexion Xtream Codes","Configurer le guide TV (XMLTV)","Définir / modifier le code parental","Effacer l’historique et les reprises","Configurer la source MTV","MTv AutoPilot • Automatisation","MTv Smart Repair • Diagnostic"};new AlertDialog.Builder(this).setTitle("GLOBAL TV • Paramètres").setItems(options,(d,w)->{if(w==0)showProfileDialog();else if(w==5)showPinDialog();else runAdultSettingsAction(()->{if(w==1)showM3uDialog();else if(w==2)showPlaylistManager();else if(w==3)showXtreamDialog();else if(w==4)showEpgDialog();else if(w==6)showClearHistoryDialog();else if(w==7)showMtvPlaylistDialog();else if(w==8)showAutoPilotDialog();else if(w==9)showSmartRepairDialog();});}).show();}
    private void showSmartRepairDialog(){
        new AlertDialog.Builder(this).setTitle("MTv Smart Repair • Diagnostic")
            .setMessage(PlaybackDiagnostics.buildReport(this))
            .setPositiveButton("Fermer",null).show();
    }

    private void showAutoPilotDialog(){
        String mode=prefs.getString("autopilot_mode","assist");
        boolean enabled=prefs.getBoolean("autopilot_enabled",false);
        String modeLabel="manual".equals(mode)?"Manuel":("auto".equals(mode)?"Automatique":"Assisté");
        String[] options={
            "Mode actuel : "+modeLabel,
            enabled?"Désactiver la planification":"Activer la planification",
            "Lancer un contrôle des sources maintenant",
            "Consulter le dernier rapport"
        };
        new AlertDialog.Builder(this).setTitle("MTv AutoPilot")
            .setMessage("Manuel : aucun contrôle en arrière-plan. Assisté : vérification toutes les 24 h. Automatique : vérification toutes les 12 h. AutoPilot contrôle uniquement les adresses des playlists M3U, sans tester chaque chaîne ni modifier tes sources.")
            .setItems(options,(dialog,which)->{
                if(which==0){
                    String[] labels={"Manuel","Assisté","Automatique"};
                    String[] values={"manual","assist","auto"};
                    int selected="manual".equals(mode)?0:("auto".equals(mode)?2:1);
                    new AlertDialog.Builder(this).setTitle("Mode AutoPilot")
                        .setSingleChoiceItems(labels,selected,(choice,index)->{
                            SharedPreferences.Editor modeEdit=prefs.edit().putString("autopilot_mode",values[index]);
                            if(index==0)modeEdit.putBoolean("autopilot_enabled",false);
                            modeEdit.apply();
                            AutoPilotScheduler.sync(this);
                            choice.dismiss();
                            showAutoPilotDialog();
                        }).setNegativeButton("Annuler",null).show();
                }else if(which==1){
                    boolean next=!enabled;
                    SharedPreferences.Editor edit=prefs.edit().putBoolean("autopilot_enabled",next);
                    if(next&&"manual".equals(mode))edit.putString("autopilot_mode","assist");
                    edit.apply();
                    AutoPilotScheduler.sync(this);
                    Toast.makeText(this,next?"Planification AutoPilot activée":"Planification AutoPilot désactivée",Toast.LENGTH_SHORT).show();
                    showAutoPilotDialog();
                }else if(which==2){
                    AutoPilotScheduler.runNow(this);
                    Toast.makeText(this,"Contrôle des sources programmé. Le rapport apparaîtra ici.",Toast.LENGTH_LONG).show();
                }else{
                    long checkedAt=prefs.getLong("autopilot_last_check",0L);
                    String when=checkedAt==0L?"Aucun contrôle effectué":new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(checkedAt));
                    String report=prefs.getString("autopilot_last_report","Aucun rapport disponible.");
                    String details=prefs.getString("autopilot_last_details","");
                    new AlertDialog.Builder(this).setTitle("Rapport AutoPilot")
                        .setMessage("Dernier contrôle : "+when+"\n"+report+(details.isEmpty()?"":"\n\n"+details))
                        .setPositiveButton("Fermer",null).show();
                }
            }).show();
    }
    private boolean isAdultWebSection(String target){return "MTV".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target);}
    private boolean isProtectedSection(String target){return target.equals("FILMS")||target.equals("SÉRIES")||target.equals("ANIMÉS")||isAdultWebSection(target);}
    private void requestParentalAccess(String target){
 String expected=prefs.getString("parental_pin_hash","");EditText pin=field("Code PIN parental");pin.setInputType(2);
 new AlertDialog.Builder(this).setTitle("Contrôle parental").setMessage("Entre le code PIN pour accéder à cette rubrique.").setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{
  if(expected.equals(hash(pin.getText().toString()))){parentalAuthorizedTarget=target;navigateTo(target);}else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
 }).show();
}
    private void showPinDialog(){
        // Le profil Enfant ne peut jamais modifier, désactiver ou réinitialiser le PIN,
        // même s'il connaît le code actuel. Cette opération reste réservée au profil Adulte.
        if(PROFILE_CHILD.equals(activeProfile)){
            Toast.makeText(this,"Passe au profil Adulte pour gérer le code parental.",Toast.LENGTH_LONG).show();
            return;
        }
        String existing=prefs.getString("parental_pin_hash","");
        if(existing.isEmpty()){showPinEditor();return;}
        EditText current=field("Code PIN actuel");current.setInputType(2);
        new AlertDialog.Builder(this).setTitle("Vérification du code parental").setMessage("Entre le code actuel avant de le modifier ou de le désactiver.").setView(current).setNegativeButton("Annuler",null).setPositiveButton("Continuer",(d,w)->{
            if(existing.equals(hash(current.getText().toString())))showPinEditor();
            else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
        }).show();
    }
    private void showPinEditor(){
 EditText pin=field("Nouveau code PIN (4 à 8 chiffres)");
 EditText confirm=field("Confirmer le nouveau code PIN");
 pin.setInputType(2);confirm.setInputType(2);
 LinearLayout form=column();
 form.addView(pin,new LinearLayout.LayoutParams(-1,dp(52)));
 form.addView(confirm,new LinearLayout.LayoutParams(-1,dp(52)));
 new AlertDialog.Builder(this).setTitle("Contrôle parental")
  .setMessage("Confirme le nouveau code pour éviter toute erreur. Laisse les deux champs vides pour désactiver le contrôle parental (profil Adulte uniquement).")
  .setView(form).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{
   String v=pin.getText().toString(), repeated=confirm.getText().toString();
   if(v.isEmpty()&&repeated.isEmpty()){
    if(PROFILE_CHILD.equals(activeProfile))Toast.makeText(this,"Passe d'abord au profil Adulte pour désactiver le code PIN.",Toast.LENGTH_LONG).show();
    else{prefs.edit().remove("parental_pin_hash").apply();Toast.makeText(this,"Contrôle parental désactivé",Toast.LENGTH_LONG).show();}
   }else if(!v.matches("\\d{4,8}")){
    Toast.makeText(this,"Utilise un code de 4 à 8 chiffres",Toast.LENGTH_LONG).show();
   }else if(!v.equals(repeated)){
    Toast.makeText(this,"Les deux codes PIN ne correspondent pas",Toast.LENGTH_LONG).show();
   }else{
    prefs.edit().putString("parental_pin_hash",hash(v)).apply();
    Toast.makeText(this,"Code parental enregistré",Toast.LENGTH_LONG).show();
   }
  }).show();
}
    private String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.US,"%02x",x));return out.toString();}catch(Exception e){return value;}}
    private String key(String value){if(value==null||value.isEmpty())return "000000000000000000000000";String digest=hash(value);if(digest==null)digest="";if(digest.length()<24){String fallback=Integer.toHexString(value.hashCode());StringBuilder padded=new StringBuilder();while(padded.length()+fallback.length()<24)padded.append("0");padded.append(fallback);digest=padded.toString();}return digest.substring(0,24);}
    private boolean isResumableEntry(MediaEntry entry){
        return entry!=null&&entry.url!=null&&!entry.url.isEmpty()
            &&!("live".equals(entry.type)||"m3u".equals(entry.type));
    }
    private void recordHistoryEntry(MediaEntry entry){
        if(entry==null||entry.url==null||entry.url.isEmpty()||prefs==null)return;
        String entryKey=key(entry.url);
        String historyKey="history_"+entryKey;
        historyKeysCache.add(historyKey);
        prefs.edit().putString("last_url",entry.url)
            .putString(historyKey,entry.name==null?"":entry.name)
            .putString("history_url_"+entryKey,entry.url)
            .putString("history_type_"+entryKey,entry.type==null?"":entry.type)
            .putString("history_group_"+entryKey,entry.group==null?"":entry.group)
            .putString("history_series_"+entryKey,entry.seriesTitle==null?"":entry.seriesTitle)
            .putInt("history_season_"+entryKey,entry.seasonNumber)
            .putInt("history_episode_"+entryKey,entry.episodeNumber)
            .putLong("history_time_"+entryKey,System.currentTimeMillis()).apply();
    }
    private void clearPlaybackPosition(MediaEntry entry){
        if(!isResumableEntry(entry)||prefs==null)return;
        prefs.edit().remove("position_"+key(entry.url)).apply();
    }
    private void savePlaybackPosition(){
        ExoPlayer active=player;
        MediaEntry entry=currentEntry;
        if(active==null||!isResumableEntry(entry)||prefs==null)return;
        try{
            long position=active.getCurrentPosition();
            long duration=active.getDuration();
            if(duration<=0L||duration==androidx.media3.common.C.TIME_UNSET)return;
            String positionKey="position_"+key(entry.url);
            if(position<=5000L||position>=duration-15000L){
                prefs.edit().remove(positionKey).apply();
                return;
            }
            prefs.edit().putLong(positionKey,position).putLong("duration_"+key(entry.url),duration).apply();
        }catch(Exception e){android.util.Log.w("MTv-Player","Unable to save playback position",e);}
    }
    private void showCreateFavoriteGroup(){EditText name=field("Nom de la liste");new AlertDialog.Builder(this).setTitle("Créer une liste de favoris").setView(name).setNegativeButton("Annuler",null).setPositiveButton("Créer",(d,w)->{String n=name.getText().toString().trim();if(n.isEmpty())return;if(favoriteGroups.containsKey(n)){Toast.makeText(this,"Cette liste existe déjà",Toast.LENGTH_SHORT).show();return;}favoriteGroups.put(n,new LinkedHashSet<>());prefs.edit().putStringSet(profileFavoriteGroupsKey(),new HashSet<>(favoriteGroups.keySet())).apply();activeFilter=n;updateChips();renderList();}).show();}
    private void showAssignFavoriteGroup(MediaEntry entry){if(favoriteGroups.isEmpty()){Toast.makeText(this,"Crée d’abord une liste de favoris.",Toast.LENGTH_SHORT).show();return;}String[] names=favoriteGroups.keySet().toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Ajouter à une liste").setItems(names,(d,i)->{Set<String> urls=favoriteGroups.get(names[i]);urls.add(entry.url);prefs.edit().putStringSet(profileFavoriteGroupKey(names[i]),new HashSet<>(urls)).apply();Toast.makeText(this,"Ajouté à "+names[i],Toast.LENGTH_SHORT).show();}).setNegativeButton("Annuler",null).show();}
    // Recherche multi-termes : les mots saisis sont normalisés une seule fois par requête.
    private void prepareSearchTokens(String query){
        String normalizedQuery=query==null?"":query.trim();
        if(!normalizedQuery.equals(cachedSearchQuery)){
            cachedSearchQuery=normalizedQuery;
            ArrayList<String> tokens=new ArrayList<>();
            for(String token:normalizedQuery.split("\\s+")){
                String normalized=epgLookupKey(token);
                if(!normalized.isEmpty())tokens.add(normalized);
            }
            cachedSearchTokens=tokens.toArray(new String[0]);
        }
    }
    private boolean matchesNormalizedSearchFields(String query,String... normalizedValues){
        if(query==null||query.trim().isEmpty())return true;
        prepareSearchTokens(query);
        if(cachedSearchTokens.length==0)return true;
        StringBuilder searchable=new StringBuilder(128);
        if(normalizedValues!=null)for(String value:normalizedValues)if(value!=null&&!value.isEmpty())searchable.append(value).append(' ');
        String haystack=searchable.toString();
        for(String token:cachedSearchTokens)if(!haystack.contains(token))return false;
        return true;
    }
    private boolean matchesSearchFields(String query,String... values){
        if(query==null||query.trim().isEmpty())return true;
        if(values==null||values.length==0)return matchesNormalizedSearchFields(query);
        StringBuilder searchable=new StringBuilder(96);
        for(String value:values){String normalized=epgLookupKey(value);if(!normalized.isEmpty())searchable.append(normalized).append(' ');}
        return matchesNormalizedSearchFields(query,searchable.toString());
    }
    private boolean matchesUniversalSearch(MediaEntry entry,String query){
        if(entry==null)return false;
        String base=normalizedSearchIndex.get(entry.url);
        if(base==null){
            StringBuilder fields=new StringBuilder(96);
            String[] values={entry.name,entry.group,entry.type};
            for(String value:values){String normalized=epgLookupKey(value);if(!normalized.isEmpty())fields.append(normalized).append(' ');}
            base=fields.toString();
            if(entry.url!=null&&!entry.url.isEmpty())normalizedSearchIndex.put(entry.url,base);
        }
        String current=epgLookupKey(findEpgProgram(epgNow,entry));
        String next=epgLookupKey(findEpgProgram(epgNext,entry));
        return matchesNormalizedSearchFields(query,base,current,next);
    }
    // Normalisation sans expressions régulières : appelée potentiellement des milliers de fois.
    private String epgLookupKey(String value){
        if(value==null||value.isEmpty())return "";
        String normalized=Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFD);
        StringBuilder out=new StringBuilder(normalized.length());
        for(int i=0;i<normalized.length();i++){
            char ch=normalized.charAt(i);
            int kind=Character.getType(ch);
            if(kind==Character.NON_SPACING_MARK||kind==Character.COMBINING_SPACING_MARK||kind==Character.ENCLOSING_MARK)continue;
            if((ch>='a'&&ch<='z')||(ch>='0'&&ch<='9'))out.append(ch);
        }
        return out.toString();
    }
    private String findEpgProgram(Map<String,String> programs,MediaEntry entry){
        if(programs==null||entry==null)return "";
        String id=epgLookupKey(entry.tvgId);
        String title=id.isEmpty()?"":programs.get(id);
        if(title==null||title.isEmpty())title=programs.get(epgLookupKey(entry.name));
        return title==null?"":title;
    }
    private String epgTimeLabel(Map<String,Long> times,MediaEntry entry){
        if(times==null||entry==null)return "";
        String id=epgLookupKey(entry.tvgId);
        Long value=id.isEmpty()?null:times.get(id);
        if(value==null)value=times.get(epgLookupKey(entry.name));
        return value==null?"":new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new java.util.Date(value));
    }
    private long epgTimeValue(Map<String,Long> times,MediaEntry entry){
        if(times==null||entry==null)return 0L;
        String id=epgLookupKey(entry.tvgId);
        Long value=id.isEmpty()?null:times.get(id);
        if(value==null)value=times.get(epgLookupKey(entry.name));
        return value==null?0L:value;
    }
    private void showEpgDialog(){if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showEpgDialog());return;}EditText input=field("URL du fichier XMLTV (HTTPS conseillé)");input.setText(epgUrl);new AlertDialog.Builder(this).setTitle("Guide TV XMLTV").setMessage("Ajoute l’URL XMLTV de ton fournisseur ou une source autorisée. Les chaînes doivent avoir un tvg-id correspondant.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){epgUrl=u;prefs.edit().putString("epg_url",u).apply();loadEpg(u);}else Toast.makeText(this,"URL HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void loadEpg(String url){
        final int request=++epgRequestId;
        if(status!=null)status.setText("Chargement du guide TV…");
        executeIo(()->{
            Map<String,String> now=new HashMap<>(),next=new HashMap<>();Map<String,Long> nowStart=new HashMap<>(),nowStop=new HashMap<>(),nextStart=new HashMap<>();
            String error=null;
            try{
                HttpURLConnection connection=open(url);
                try{
                    int response=connection.getResponseCode();
                    if(response<200||response>=300)throw new IOException("HTTP "+response);
                    InputStream input=connection.getInputStream();
                    if(input==null)throw new IOException("Réponse XMLTV vide");
                    XmlPullParser parser=XmlPullParserFactory.newInstance().newPullParser();
                    parser.setInput(input,"UTF-8");
                    String channel=null;long start=0L,stop=0L,nowMs=System.currentTimeMillis();
                    int event=parser.getEventType(),events=0,programmes=0;
                    while(event!=XmlPullParser.END_DOCUMENT){
                        if(++events>500000)throw new IOException("Guide TV trop volumineux");
                        if(event==XmlPullParser.START_TAG&&"programme".equals(parser.getName())){
                            if(++programmes>100000)throw new IOException("Trop de programmes dans le guide TV");
                            channel=parser.getAttributeValue(null,"channel");
                            start=parseXmlTvTime(parser.getAttributeValue(null,"start"));
                            stop=parseXmlTvTime(parser.getAttributeValue(null,"stop"));
                        }else if(event==XmlPullParser.START_TAG&&"title".equals(parser.getName())&&channel!=null){
                            String title=parser.nextText();
                            if(title!=null&&!title.trim().isEmpty()){
                                title=title.trim();
                                if(title.length()>300)title=title.substring(0,300);
                                String channelKey=epgLookupKey(channel);if(!channelKey.isEmpty()&&start<=nowMs&&stop>nowMs&&now.size()<20000){if(!now.containsKey(channelKey)){now.put(channelKey,title);nowStart.put(channelKey,start);nowStop.put(channelKey,stop);}}
                                else if(!channelKey.isEmpty()&&start>nowMs&&stop>start&&next.size()<20000){Long previousStart=nextStart.get(channelKey);if(previousStart==null||start<previousStart){nextStart.put(channelKey,start);next.put(channelKey,title);}}
                            }
                        }
                        event=parser.next();
                    }
                }finally{connection.disconnect();}
            }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            if(request!=epgRequestId||destroyed)return;
            final String failure=error;
            epgNow.clear();epgNow.putAll(now);epgNowStart.clear();epgNowStart.putAll(nowStart);epgNowStop.clear();epgNowStop.putAll(nowStop);
            epgNext.clear();epgNext.putAll(next);epgNextStart.clear();epgNextStart.putAll(nextStart);
            postUi(()->{
                if(request!=epgRequestId)return;
                if(status!=null)status.setText(failure==null?now.size()+" programmes en cours • "+next.size()+" à suivre • Guide TV XMLTV":"Erreur EPG : "+failure);
                renderList();
            });
        });
    }
    private long parseXmlTvTime(String value){
        if(value==null)return 0L;
        try{
            String v=value.trim();
            if(v.length()==14)v+=" +0000";
            else if(v.length()==15&&v.charAt(14)=='Z')v=v.substring(0,14)+" +0000";
            else if(v.length()==19&&(v.charAt(14)=='+'||v.charAt(14)=='-'))v=v.substring(0,14)+" "+v.substring(14);
            if(!v.matches("\\d{14} [+-]\\d{4}"))return 0L;
            SimpleDateFormat format=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);
            format.setLenient(false);
            Date parsed=format.parse(v);
            return parsed==null?0L:parsed.getTime();
        }catch(Exception ignored){return 0L;}
    }
    private void showM3uDialog(){if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showM3uDialog());return;}EditText input=new EditText(this);input.setSingleLine(true);input.setHint("https://exemple.com/playlist.m3u");input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);input.setText("");new AlertDialog.Builder(this).setTitle("Ajouter une playlist M3U").setMessage("Ajoute une nouvelle source. Les chaînes des playlists seront réunies dans le même catalogue.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Ajouter et charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){section="TV EN DIRECT";refreshAllNavStyles();loadPlaylist(u);}else Toast.makeText(this,"Adresse HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void showPlaylistManager(){if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showPlaylistManager());return;}ArrayList<String> urls=new ArrayList<>(playlistSources);if(urls.isEmpty()){new AlertDialog.Builder(this).setTitle("Sources M3U").setMessage("Aucune source enregistrée.").setPositiveButton("Ajouter", (d,w)->showM3uDialog()).show();return;}String[] labels=new String[urls.size()];for(int i=0;i<urls.size();i++)labels[i]=(i+1)+". "+Uri.parse(urls.get(i)).getHost()+" — "+urls.get(i);new AlertDialog.Builder(this).setTitle("Sources M3U • "+urls.size()).setItems(labels,(d,which)->new AlertDialog.Builder(this).setTitle("Gérer cette source").setMessage(urls.get(which)).setNegativeButton("Fermer",null).setNeutralButton("Supprimer",(a,b)->{playlistSources.remove(urls.get(which));prefs.edit().putStringSet("m3u_sources",new HashSet<>(playlistSources)).apply();loadAllPlaylists();}).setPositiveButton("Recharger",(a,b)->loadPlaylist(urls.get(which))).show()).setPositiveButton("Ajouter une source",(d,w)->showM3uDialog()).setNegativeButton("Fermer",null).show();}
    private void showXtreamDialog(){if(!consumeAdultSettingsAccess()){runAdultSettingsAction(()->showXtreamDialog());return;}LinearLayout form=column();form.setPadding(dp(8),dp(4),dp(8),dp(4));EditText host=field("URL du serveur (https://serveur:port)");EditText user=field("Nom d’utilisateur");EditText pass=field("Mot de passe");pass.setInputType(129);form.addView(host);form.addView(user);form.addView(pass);new AlertDialog.Builder(this).setTitle("Connexion Xtream Codes").setMessage("Utilise les identifiants fournis par ton fournisseur IPTV autorisé. HTTPS est recommandé.").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Se connecter",(d,w)->{String h=host.getText().toString().trim(),u=user.getText().toString().trim(),p=pass.getText().toString();try{Uri uri=Uri.parse(h);if((!"https".equalsIgnoreCase(uri.getScheme())&&!("http".equalsIgnoreCase(uri.getScheme())))||uri.getHost()==null||u.isEmpty()||p.isEmpty())throw new Exception();xtreamBase=h;xtreamUser=u;xtreamPass=p;section="ACCUEIL";heading.setText("Bienvenue sur GLOBAL TV");loadXtream();}catch(Exception e){Toast.makeText(this,"Vérifie l’URL et les identifiants",Toast.LENGTH_LONG).show();}}).show();}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(10),dp(6),dp(10),dp(6));return e;}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&player!=null&&playerView!=null){
            int code=event.getKeyCode();
            boolean next=code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_CHANNEL_UP||code==KeyEvent.KEYCODE_PAGE_DOWN||code==KeyEvent.KEYCODE_P;
            boolean previous=code==KeyEvent.KEYCODE_MEDIA_PREVIOUS||code==KeyEvent.KEYCODE_CHANNEL_DOWN||code==KeyEvent.KEYCODE_PAGE_UP;
            if(next){if(player.hasNextMediaItem())player.seekToNextMediaItem();return true;}
            if(previous){if(player.hasPreviousMediaItem())player.seekToPreviousMediaItem();return true;}
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(webCustomView!=null){hideWebCustomView();return true;}
            if(isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE){if(mtvWebView.canGoBack()){mtvWebView.goBack();return true;}navigateTo("ACCUEIL");return true;}
            if(playerView!=null){if(playerFullscreen){togglePlayerExpanded();return true;}savePlaybackPosition();releasePlayer();if(status!=null)status.setVisibility(View.GONE);return true;}
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&isWebSection(section)&&webContainer!=null&&webContainer.getVisibility()==View.VISIBLE&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&mtvWebView!=null&&mtvWebView.hasFocus()&&!mtvWebView.canScrollVertically(-1)){
            View menu=null;View webNav=webContainer.getChildAt(0);if(webNav instanceof ViewGroup)menu=((ViewGroup)webNav).getChildAt(0);if(menu!=null){menu.requestFocus();return true;}
        }
        if(isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE&&event.getAction()==KeyEvent.ACTION_DOWN&&(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_CENTER||event.getKeyCode()==KeyEvent.KEYCODE_ENTER)&&!mtvWebView.hasFocus())mtvWebView.requestFocus();
        return super.dispatchKeyEvent(event);
    }
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
    @Override protected void onStart(){super.onStart();if(resumePlaybackOnStart&&player!=null){resumePlaybackOnStart=false;player.play();}if(playbackSaveRunnable!=null)mainHandler.postDelayed(playbackSaveRunnable,15000L);}
    @Override protected void onStop(){super.onStop();if(playbackSaveRunnable!=null)mainHandler.removeCallbacks(playbackSaveRunnable);if(player!=null){resumePlaybackOnStart=player.isPlaying();savePlaybackPosition();player.pause();}}
    @Override protected void onDestroy(){
        destroyed=true;catalogRequestId++;mtvPlaylistRequestId++;epgRequestId++;if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
        if(webCustomView!=null)hideWebCustomView();
        for(WebView popup:new HashSet<>(webPopupViews)){try{popup.stopLoading();popup.destroy();}catch(Exception ignored){}}webPopupViews.clear();
        if(mtvWebView!=null){try{mtvWebView.stopLoading();mtvWebView.loadUrl("about:blank");mtvWebView.clearHistory();if(mtvWebView.getParent() instanceof ViewGroup)((ViewGroup)mtvWebView.getParent()).removeView(mtvWebView);mtvWebView.destroy();}catch(Exception ignored){}mtvWebView=null;}
        savePlaybackPosition();releasePlayer();io.shutdownNow();super.onDestroy();
    }
}

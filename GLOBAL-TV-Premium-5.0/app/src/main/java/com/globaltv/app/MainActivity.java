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
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

/** GLOBAL TV Premium - Android TV / Google TV. */
@UnstableApi
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
    private volatile boolean destroyed=false;
    private volatile int catalogRequestId=0;
    private volatile int epgRequestId=0;
    private volatile int seriesRequestId=0;
    private String parentalAuthorizedTarget="";
    private boolean resumePlaybackOnStart=false;
    private BrandSplash brandSplash;
    private long splashStartedAt=0L;
    private boolean splashLoadFinished=false;
    private Runnable splashTimeoutRunnable;
    private static final int MAX_CATALOG_ITEMS=20000;
    private int maxRenderItems=120;
    private int renderOffset=0;
    private String activeProfile="Adulte";
    private static final String PROFILE_ADULT="Adulte", PROFILE_CHILD="Enfant";
    private final ArrayList<MediaEntry> all=new ArrayList<>();
    // Catalogue isolé réservé aux sources MTV que l'utilisateur est autorisé à utiliser.
    private final ArrayList<MediaEntry> mtvItems=new ArrayList<>();
    private String mtvPlaylistUrl="";
    private final Set<String> favorites=new HashSet<>();
    private final Set<String> historyKeysCache=new HashSet<>();
    private final Map<String,String> epgNow=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Set<String>> favoriteGroups=new LinkedHashMap<>();
    private final Map<String,String> epgNext=new ConcurrentHashMap<>();
    private SharedPreferences prefs;
    private static final String PREFS_KEY_ALIAS="GLOBAL_TV_LOCAL_DATA_V1";
    private boolean secureStorageReadFailed=false;
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
    private final Set<WebView> webPopupViews=new HashSet<>();
    private String activeWebUrl="";
    private boolean playerExpanded=false;
    private boolean playerFullscreen=false;
    private View playerFullscreenTop,playerFullscreenChips,playerFullscreenStatus,playerFullscreenFooter;
    private MediaEntry currentEntry;
    private AlertDialog seriesDialog;
    private final Set<String> failedInCurrentRun=new HashSet<>();
    private ExoPlayer playbackRecoveryPlayer;
    private Runnable playbackRecoveryRunnable;
    private Runnable bufferingWatchdog;
    private ExoPlayer bufferingWatchdogPlayer;
    private Runnable playbackPositionSaver;
    private android.net.ConnectivityManager connectivityManager;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered=false,networkUnavailable=false;
    private Runnable networkRecoveryRunnable;
    private EditText search;
    private TextView status,heading,sourceLabel;
    private PlayerView playerView;
    private ExoPlayer player;
    private int playerResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
    private String section="ACCUEIL",activeFilter="Tout",sourceMode="M3U",xtreamBase="",xtreamUser="",xtreamPass="";
    private String restorePlaybackUrl="",restorePlaybackName="";
    private long restorePlaybackPosition=0L;
    private boolean restorePlaybackWasPlaying=true,restorePlayerFullscreen=false;
    private boolean pendingPlayerStateRestore=false;
    private Bundle pendingWebViewState;

    static class MediaEntry {
        String name,url,group,logo,type,id,container,tvgId;
        Map<String,String> httpHeaders=new HashMap<>();
        MediaEntry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;id="";container="m3u8";tvgId="";}
    }
    private javax.crypto.SecretKey getLocalEncryptionKey() throws Exception {
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing=store.getKey(PREFS_KEY_ALIAS,null);
        if(existing instanceof javax.crypto.SecretKey)return (javax.crypto.SecretKey)existing;
        javax.crypto.KeyGenerator generator=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(PREFS_KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private String encryptLocalValue(String value,String preferenceKey) throws Exception {
        return encryptLocalValue(value,preferenceKey,getLocalEncryptionKey());
    }
    private String encryptLocalValue(String value,String preferenceKey,javax.crypto.SecretKey secretKey) throws Exception {
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,secretKey);
        cipher.updateAAD(preferenceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] packed=new byte[cipher.getIV().length+encrypted.length];
        System.arraycopy(cipher.getIV(),0,packed,0,cipher.getIV().length);
        System.arraycopy(encrypted,0,packed,cipher.getIV().length,encrypted.length);
        return "enc:v1:"+android.util.Base64.encodeToString(packed,android.util.Base64.NO_WRAP);
    }
    private String decryptLocalValue(String value,String preferenceKey) throws Exception {
        return decryptLocalValue(value,preferenceKey,getLocalEncryptionKey());
    }
    private String decryptLocalValue(String value,String preferenceKey,javax.crypto.SecretKey secretKey) throws Exception {
        if(value==null||!value.startsWith("enc:v1:"))return value;
        byte[] packed=android.util.Base64.decode(value.substring(7),android.util.Base64.NO_WRAP);
        if(packed.length<29)throw new java.security.GeneralSecurityException("Donnée chiffrée invalide");
        byte[] iv=java.util.Arrays.copyOfRange(packed,0,12);
        byte[] encrypted=java.util.Arrays.copyOfRange(packed,12,packed.length);
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,secretKey,new javax.crypto.spec.GCMParameterSpec(128,iv));
        cipher.updateAAD(preferenceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new String(cipher.doFinal(encrypted),java.nio.charset.StandardCharsets.UTF_8);
    }
    private String getSecureString(String preferenceKey,String fallback){
        String stored=prefs.getString(preferenceKey,null);
        if(stored==null)return fallback;
        if(stored.startsWith("enc:v1:")){
            try{return decryptLocalValue(stored,preferenceKey);}
            catch(Exception error){secureStorageReadFailed=true;android.util.Log.e("GLOBALTV","Impossible de déchiffrer "+preferenceKey,error);return fallback;}
        }
        try{
            String encrypted=encryptLocalValue(stored,preferenceKey);
            if(prefs.edit().putString(preferenceKey,encrypted).commit())return stored;
        }catch(Exception error){android.util.Log.e("GLOBALTV","Migration chiffrée impossible pour "+preferenceKey,error);}
        return stored;
    }
    private boolean putSecureString(String preferenceKey,String value){
        if(secureStorageReadFailed)return false;
        try{return prefs.edit().putString(preferenceKey,encryptLocalValue(value==null?"":value,preferenceKey)).commit();}
        catch(Exception error){android.util.Log.e("GLOBALTV","Écriture chiffrée impossible pour "+preferenceKey,error);return false;}
    }
    private Set<String> getSecureStringSet(String preferenceKey,Set<String> fallback){
        Set<String> stored=prefs.getStringSet(preferenceKey,null);
        if(stored==null)return new HashSet<>(fallback);
        HashSet<String> result=new HashSet<>(),encoded=new HashSet<>();
        boolean changed=false,failed=false;
        javax.crypto.SecretKey secretKey;
        try{secretKey=getLocalEncryptionKey();}catch(Exception error){secureStorageReadFailed=true;android.util.Log.e("GLOBALTV","Clé de chiffrement indisponible",error);boolean containsCiphertext=false;for(String item:stored)if(item!=null&&item.startsWith("enc:v1:"))containsCiphertext=true;return containsCiphertext?new HashSet<>():new HashSet<>(stored);}
        for(String value:stored){
            if(value!=null&&value.startsWith("enc:v1:")){
                try{result.add(decryptLocalValue(value,preferenceKey,secretKey));encoded.add(value);}
                catch(Exception error){failed=true;secureStorageReadFailed=true;android.util.Log.e("GLOBALTV","Élément chiffré illisible dans "+preferenceKey,error);}
            }else{
                result.add(value);
                try{encoded.add(encryptLocalValue(value,preferenceKey,secretKey));changed=true;}
                catch(Exception error){secureStorageReadFailed=true;android.util.Log.e("GLOBALTV","Migration de liste impossible pour "+preferenceKey,error);return result;}
            }
        }
        if(failed){secureStorageReadFailed=true;return result;}
        if(changed&&!prefs.edit().putStringSet(preferenceKey,encoded).commit())
            android.util.Log.e("GLOBALTV","Échec de validation de la migration "+preferenceKey);
        return result;
    }
    private boolean putSecureStringSet(String preferenceKey,Set<String> values){
        if(secureStorageReadFailed)return false;
        try{
            javax.crypto.SecretKey secretKey=getLocalEncryptionKey();
            HashSet<String> encoded=new HashSet<>();
            for(String value:values)encoded.add(encryptLocalValue(value,preferenceKey,secretKey));
            return prefs.edit().putStringSet(preferenceKey,encoded).commit();
        }catch(Exception error){android.util.Log.e("GLOBALTV","Écriture de liste chiffrée impossible pour "+preferenceKey,error);return false;}
    }
    private void migrateSensitivePreferences(){
        getSecureStringSet("favorites",new HashSet<>());
        getSecureStringSet("m3u_sources",new HashSet<>());
        getSecureString("epg_url","");
        getSecureString("mtv_playlist_url","");
        for(String group:prefs.getStringSet("favorite_groups",new HashSet<>()))
            getSecureStringSet("favorite_group_"+key(group),new HashSet<>());
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);installBackNavigation();WindowCompat.setDecorFitsSystemWindows(getWindow(),false);applyImmersiveMode();getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);prefs=getPreferences(0);
        if(b!=null){section=b.getString("session_section","ACCUEIL");activeFilter=b.getString("session_filter","Tout");String savedPlayerUrl=b.getString("session_player_url","");try{restorePlaybackUrl=savedPlayerUrl.startsWith("enc:v1:")?decryptLocalValue(savedPlayerUrl,"session_player_url"):savedPlayerUrl;}catch(Exception secureError){restorePlaybackUrl="";android.util.Log.e("GLOBALTV","État de lecture chiffré illisible",secureError);}restorePlaybackName=b.getString("session_player_name","Reprise");restorePlaybackPosition=b.getLong("session_player_position",0L);restorePlaybackWasPlaying=b.getBoolean("session_player_playing",true);restorePlayerFullscreen=b.getBoolean("session_player_fullscreen",false);pendingWebViewState=null;}
        activeProfile=prefs.getString("active_profile",PROFILE_ADULT);
        configureMemoryBudget();
        migrateSensitivePreferences();if(secureStorageReadFailed)Toast.makeText(this,"Certaines données protégées sont momentanément illisibles. Elles n’ont pas été supprimées.",Toast.LENGTH_LONG).show();favorites.addAll(getSecureStringSet("favorites",new HashSet<>()));for(String group:prefs.getStringSet("favorite_groups",new HashSet<>()))favoriteGroups.put(group,new LinkedHashSet<>(getSecureStringSet("favorite_group_"+key(group),new HashSet<>())));playlistSources.addAll(getSecureStringSet("m3u_sources",new LinkedHashSet<>()));
        playlistSources.add(DEFAULT_PLAYLIST);
        playlistSources.addAll(Arrays.asList(BUILT_IN_PLAYLISTS));
        putSecureStringSet("m3u_sources",new LinkedHashSet<>(playlistSources));
        epgUrl=getSecureString("epg_url","");mtvPlaylistUrl=getSecureString("mtv_playlist_url","");lastPlayedUrl="";prefs.edit().remove("last_url").apply();buildUi();if(b!=null){if(search!=null)search.setText(b.getString("session_search",""));String savedWebUrl=b.getString("session_web_url","");try{if(savedWebUrl.startsWith("enc:v1:"))savedWebUrl=decryptLocalValue(savedWebUrl,"session_web_url");}catch(Exception secureError){savedWebUrl="";android.util.Log.e("GLOBALTV","État Web chiffré illisible",secureError);}if(isWebSection(section))showSiteWebView(savedWebUrl.isEmpty()?webUrlFor(section):savedWebUrl);
            if(!restorePlaybackUrl.isEmpty()){if(restorePlaybackPosition>0L)prefs.edit().putLong("position_"+key(restorePlaybackUrl),restorePlaybackPosition).apply();MediaEntry restored=new MediaEntry(restorePlaybackName,restorePlaybackUrl,b.getString("session_player_group","Reprise"),"", "live");pendingPlayerStateRestore=true;startPlayer(restored);}}
        if(b==null)showBrandSplash();loadAllPlaylists();if(!epgUrl.isEmpty())loadEpg(epgUrl);}
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
            progressBar.setProgress(1000);
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
        sidebarScroll=new ScrollView(this);sidebarScroll.setFillViewport(true);sidebarScroll.setVerticalScrollBarEnabled(true);sidebarScroll.setScrollbarFadingEnabled(false);sidebarScroll.setSmoothScrollingEnabled(true);sidebarScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);root.addView(sidebarScroll,new LinearLayout.LayoutParams(dp(220),-1));
        sidebar=column();sidebar.setPadding(dp(18),dp(22),dp(12),dp(14));sidebar.setBackgroundColor(0xFF0D1525);sidebarScroll.addView(sidebar,new ScrollView.LayoutParams(-1,-2));
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER);TextView dish=text("📡",25,0xFFFFC45C,true);dish.setGravity(Gravity.CENTER);brand.addView(dish,new LinearLayout.LayoutParams(dp(38),dp(62)));TextView brandName=text("MTv",50,0xFFFFB52E,true);brandName.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC));brandName.setGravity(Gravity.CENTER);brandName.setLetterSpacing(0.025f);brandName.setShadowLayer(dp(8),0,dp(2),0xFFFF654F);brand.addView(brandName,new LinearLayout.LayoutParams(-2,dp(68)));LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(-1,dp(72));brandParams.bottomMargin=dp(5);sidebar.addView(brand,brandParams);
        addNav("⌂   Accueil","ACCUEIL");addNav("▣   TV en direct","TV EN DIRECT");addNav("♫   Musique","MUSIQUE");addNav("▤   Infos","INFOS");addNav("▶   Films","FILMS");addNav("▤   Séries","SÉRIES");addNav("✦   Animés","ANIMÉS");addNav("⚽   Sports","SPORTS");addNav("★   Divertissement","DIVERTISSEMENT");addNav("▤   Magazine","MAGAZINE");addNav("♟   Jeunesse","JEUNESSE");addNav("🎓   Éducation","ÉDUCATION");addNav("♡   Mes favoris","FAVORIS");addNav("◷   Historique","HISTORIQUE");addNav("▦   Guide TV","GUIDE TV");addNav("🌐   AOXTv","MTV");addNav("🌐   WiTv","WITV");addNav("🌐   FrSTv","FRSTV");addNav("🌐   FsLive","FSLIVE");addNav("🌐   EpiXtv","EPI XTV");addNav("🌐   DudeTV","DUDETV");addNav("🌐   Braflix","BRAFLIX");
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
        search.setNextFocusLeftId(navViews.get("ACCUEIL").getId());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
                pendingSearch=()->{renderOffset=0;if(!destroyed)renderList();};
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
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.bottomMargin=dp(3);sidebar.addView(v,p);v.setOnClickListener(w->navigateTo(target));refreshNavStyle(target,v.hasFocus());}
    private void refreshNavStyle(String target,boolean focused){TextView v=navViews.get(target);if(v==null)return;boolean selected=section.equals(target);if(selected){GradientDrawable d=bg(0xFF6E54FF,10);d.setStroke(dp(2),0xFF42E8F5);v.setBackground(d);v.setTextColor(Color.WHITE);}else if(focused){GradientDrawable d=bg(FOCUS_GOLD,10);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);v.setTextColor(0xFF17111A);}else{v.setBackground(bg(0x00172035,10));v.setTextColor(0xFFD9E1F2);}}
    private void refreshAllNavStyles(){for(String target:navViews.keySet())refreshNavStyle(target,navViews.get(target).hasFocus());}
    private void navigateTo(String target){
 if(PROFILE_CHILD.equals(activeProfile)&&isAdultWebSection(target)){Toast.makeText(this,"Cette rubrique est réservée au profil Adulte.",Toast.LENGTH_LONG).show();return;}
 String pinHash=prefs.getString("parental_pin_hash","");
 if(isProtectedSection(target)&&!pinHash.isEmpty()&&!target.equals(parentalAuthorizedTarget)){requestParentalAccess(target);return;}
 if(target.equals(parentalAuthorizedTarget))parentalAuthorizedTarget="";
 // Ferme toute vidéo HTML5 plein écran avant de changer de rubrique.
 if(webCustomView!=null)hideWebCustomView();
 if(playerFullscreen)togglePlayerExpanded();
 seriesRequestId++;dismissSeriesDialog();
 section=target;renderOffset=0;refreshAllNavStyles();activeFilter="Tout";
 if(pendingSearch!=null){mainHandler.removeCallbacks(pendingSearch);pendingSearch=null;}
 search.setText("");search.setHint("⌕  Rechercher…");updateChips();TextView targetNav=navViews.get(target);if(targetNav!=null)search.setNextFocusLeftId(targetNav.getId());
 if(catalogScroll!=null)catalogScroll.scrollTo(0,0);
 if(isWebSection(target)){showSiteWebView(webUrlFor(target));return;}
        hideMtvWebView();
 renderList();
 if(target.equals("PARAMÈTRES"))showSettings();else{TextView selectedNav=navViews.get(target);if(selectedNav!=null)selectedNav.requestFocus();}
}
    private void updateChips(){if(chips==null)return;chips.removeAllViews();String[] opts;if(section.equals("TV EN DIRECT"))opts=new String[]{"Tout","France","Afrique","USA","Canada","Germany","Espagne","Angleterre","Sports","News","Music","Kids","Réligion","Documentaire"};else if(section.equals("FAVORIS")){ArrayList<String> values=new ArrayList<>();values.add("Tout");values.addAll(favoriteGroups.keySet());values.add("＋ Créer une liste");opts=values.toArray(new String[0]);}else if(section.equals("HISTORIQUE")||section.equals("GUIDE TV")||section.equals("MUSIQUE")||section.equals("INFOS")||section.equals("SPORTS")||section.equals("DIVERTISSEMENT")||section.equals("MAGAZINE")||section.equals("JEUNESSE")||section.equals("ÉDUCATION"))opts=new String[]{"Tout"};else opts=new String[]{"Tout","Action","Comédie","Drame","Aventure","Fantasy","Science-fiction","Animation"};int previousId=View.NO_ID;for(String value:opts){final String filter=value;TextView c=text(value,12,activeFilter.equals(value)?Color.WHITE:0xFFB8C4D8,true);c.setGravity(Gravity.CENTER);c.setPadding(dp(15),0,dp(15),0);c.setBackground(bg(activeFilter.equals(value)?ACCENT:CARD,18));c.setFocusable(true);c.setFocusableInTouchMode(true);c.setClickable(true);c.setId(View.generateViewId());c.setNextFocusUpId(search.getId());if(previousId!=View.NO_ID)c.setNextFocusLeftId(previousId);if(previousId!=View.NO_ID)chips.getChildAt(chips.getChildCount()-1).setNextFocusRightId(c.getId());previousId=c.getId();c.setOnFocusChangeListener((v,focus)->{if(focus){if(filter.equals(activeFilter)){GradientDrawable d=bg(ACCENT,18);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);((TextView)v).setTextColor(Color.WHITE);}else{GradientDrawable d=bg(FOCUS_GOLD,18);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);((TextView)v).setTextColor(0xFF17111A);}Rect rect=new Rect();v.getDrawingRect(rect);chipScroll.requestChildRectangleOnScreen(v,rect,true);}else{v.setBackground(bg(filter.equals(activeFilter)?ACCENT:CARD,18));((TextView)v).setTextColor(filter.equals(activeFilter)?Color.WHITE:0xFFB8C4D8);}});c.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){v.performClick();return true;}if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_DPAD_LEFT){int idx=chips.indexOfChild(v),next=idx+(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);if(next>=0&&next<chips.getChildCount()){chips.getChildAt(next).requestFocus();return true;}}if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN){if(list.getChildCount()>0){list.getChildAt(0).requestFocus();return true;}}return false;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.rightMargin=dp(8);chips.addView(c,p);c.setOnClickListener(v->{if(section.equals("FAVORIS")&&filter.equals("＋ Créer une liste")){showCreateFavoriteGroup();return;}activeFilter=filter;renderOffset=0;updateChips();renderList();for(int i=0;i<chips.getChildCount();i++){View selected=chips.getChildAt(i);if(selected instanceof TextView&&filter.equals(((TextView)selected).getText().toString())){selected.requestFocus();break;}}});}search.setNextFocusDownId(chips.getChildAt(0).getId());}


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
        if(playerArea!=null)playerArea.setOrientation(LinearLayout.HORIZONTAL);if(catalogScroll!=null&&catalogScroll.getParent()!=playerArea)playerArea.addView(catalogScroll,new LinearLayout.LayoutParams(-1,-1));
        if(playerToolbar!=null){if(playerToolbar.getParent() instanceof ViewGroup)((ViewGroup)playerToolbar.getParent()).removeView(playerToolbar);playerToolbar=null;}if(playerArea!=null){for(int i=playerArea.getChildCount()-1;i>=0;i--){View child=playerArea.getChildAt(i);if(child instanceof LinearLayout&&child!=catalogScroll&&((LinearLayout)child).getChildCount()==0)playerArea.removeViewAt(i);}}
        clearPlaybackRecovery(null);
        stopBufferingWatchdog(null);
        stopPlaybackPositionSaver();
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
    }
    private HttpURLConnection open(String address)throws Exception{
        URL current=new URL(address);
        for(int redirect=0;redirect<=5;redirect++){
            String scheme=current.getProtocol();
            if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))throw new IOException("Protocole réseau non pris en charge");
            HttpURLConnection connection=(HttpURLConnection)current.openConnection();
            try{
                connection.setConnectTimeout(10000);connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent","GLOBAL-TV-Android/5.0");
                connection.setRequestProperty("Accept","*/*");
                connection.setInstanceFollowRedirects(false);
                int code=connection.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String location=connection.getHeaderField("Location");
                    if(location==null||location.trim().isEmpty())throw new IOException("Redirection sans destination");
                    URL next=new URL(current,location);
                    String nextScheme=next.getProtocol();
                    if(!"http".equalsIgnoreCase(nextScheme)&&!"https".equalsIgnoreCase(nextScheme))throw new IOException("Protocole de redirection non autorisé");
                    connection.disconnect();current=next;continue;
                }
                return connection;
            }catch(Exception e){
                connection.disconnect();
                throw e;
            }
        }
        throw new IOException("Trop de redirections réseau");
    }
    private String get(String address)throws Exception{HttpURLConnection c=open(address);try{int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(code<200||code>=300)throw new IOException("Serveur HTTP "+code);if(in==null)throw new IOException("Réponse vide du serveur");
            try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n,total=0;
                while((n=stream.read(b))!=-1){total+=n;if(total>15*1024*1024)throw new IOException("Réponse trop volumineuse (limite 15 Mo)");out.write(b,0,n);}
                return out.toString("UTF-8");
            }}finally{c.disconnect();}}
    private void loadPlaylist(String url){if(url!=null&&(url.startsWith("http://")||url.startsWith("https://"))){playlistSources.add(url);putSecureStringSet("m3u_sources",new HashSet<>(playlistSources));}loadAllPlaylists();}
    // Applique une vraie limite en octets avant le décodage UTF-8 de la playlist.
    private InputStream limitM3uStream(InputStream source,long maxBytes){
        return new FilterInputStream(source){
            private long total=0L;
            @Override public int read()throws IOException{
                int value=super.read();
                if(value!=-1&&++total>maxBytes)throw new IOException("Playlist trop volumineuse (limite 64 Mo)");
                return value;
            }
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{
                int count=super.read(buffer,offset,length);
                if(count>0){total+=count;if(total>maxBytes)throw new IOException("Playlist trop volumineuse (limite 64 Mo)");}
                return count;
            }
        };
    }
    private String resolveM3uEntryUrl(String playlistAddress,String entryAddress){
        if(playlistAddress==null||entryAddress==null||entryAddress.trim().isEmpty())return null;
        try{
            URL resolved=new URL(new URL(playlistAddress),entryAddress.trim());
            String protocol=resolved.getProtocol();
            if(!("http".equalsIgnoreCase(protocol)||"https".equalsIgnoreCase(protocol))||resolved.getHost()==null||resolved.getHost().isEmpty())return null;
            return resolved.toExternalForm();
        }catch(Exception ignored){return null;}
    }
    private void parseM3uHttpOption(String line,Map<String,String> headers){
        if(line==null||headers==null)return;
        try{
            if(line.startsWith("#EXTVLCOPT:")){
                String option=line.substring(11).trim();int split=option.indexOf('=');if(split<=0)return;
                String key=option.substring(0,split).trim().toLowerCase(Locale.ROOT);String value=option.substring(split+1).trim();
                if(key.equals("http-user-agent"))putM3uHeader(headers,"User-Agent",value);
                else if(key.equals("http-referrer")||key.equals("http-referer"))putM3uHeader(headers,"Referer",value);
                else if(key.equals("http-cookie"))putM3uHeader(headers,"Cookie",value);
                else if(key.equals("http-origin"))putM3uHeader(headers,"Origin",value);
                else if(key.equals("http-authorization"))putM3uHeader(headers,"Authorization",value);
            }else if(line.startsWith("#EXTHTTP:")){
                JSONObject json=new JSONObject(line.substring(9).trim());java.util.Iterator<String> keys=json.keys();
                while(keys.hasNext()){
                    String key=keys.next();String lower=key.toLowerCase(Locale.ROOT);String headerValue=json.optString(key,"");
                    if(lower.equals("user-agent"))putM3uHeader(headers,"User-Agent",headerValue);
                    else if(lower.equals("referer")||lower.equals("referrer"))putM3uHeader(headers,"Referer",headerValue);
                    else if(lower.equals("cookie"))putM3uHeader(headers,"Cookie",headerValue);
                    else if(lower.equals("origin"))putM3uHeader(headers,"Origin",headerValue);
                    else if(lower.equals("authorization"))putM3uHeader(headers,"Authorization",headerValue);
                }
            }
        }catch(Exception ignored){}
    }
    private void putM3uHeader(Map<String,String> headers,String name,String value){
        if(value==null||value.isEmpty()||value.length()>2048||value.indexOf('\r')>=0||value.indexOf('\n')>=0)return;
        headers.put(name,value);
    }
    private int appendM3uPlaylist(String address,ArrayList<MediaEntry> merged,int request)throws Exception{
 HttpURLConnection connection=open(address);
 try{
  int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("Serveur HTTP "+code);
  InputStream input=connection.getInputStream();if(input==null)throw new IOException("Réponse vide du serveur");
  int count=0;
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(limitM3uStream(input,64L*1024L*1024L),"UTF-8"))){
   String line,name=null,group="Autres",logo="",tvgId="";Map<String,String> streamHeaders=new HashMap<>();
   while((line=reader.readLine())!=null&&merged.size()<MAX_CATALOG_ITEMS&&request==catalogRequestId&&!destroyed){
    if(line.length()>1024*1024)throw new IOException("Ligne M3U trop longue (limite 1 Mo)");
    line=line.trim();if(line.startsWith("\uFEFF"))line=line.substring(1);
    if(line.startsWith("#EXTINF:")){streamHeaders.clear();
     name=line.substring(line.lastIndexOf(',')+1).trim();group="Autres";logo="";tvgId="";
     int g=line.indexOf("group-title=\"");if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}
     int tid=line.indexOf("tvg-id=\"");if(tid>=0){int te=line.indexOf('"',tid+8);if(te>tid)tvgId=line.substring(tid+8,te);}
     int l=line.indexOf("tvg-logo=\"");if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}
    }else if(line.startsWith("#EXTVLCOPT:")||line.startsWith("#EXTHTTP:")){parseM3uHttpOption(line,streamHeaders);
    }else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){
     String resolvedUrl=resolveM3uEntryUrl(connection.getURL().toString(),line);
     if(resolvedUrl!=null){MediaEntry entry=new MediaEntry(name,resolvedUrl,group,logo,"m3u");entry.tvgId=tvgId;entry.httpHeaders.putAll(streamHeaders);merged.add(entry);count++;}
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
  ArrayList<MediaEntry> merged=new ArrayList<>();ArrayList<String> errors=new ArrayList<>();
  for(String address:urls){
   if(merged.size()>=MAX_CATALOG_ITEMS||request!=catalogRequestId||destroyed)break;
   try{appendM3uPlaylist(address,merged,request);}catch(Exception ex){String host=Uri.parse(address).getHost();errors.add((host==null?address:host)+": "+ex.getMessage());}
  }
  if(request!=catalogRequestId||destroyed)return;final int loaded=merged.size();
  postUi(()->{if(request!=catalogRequestId)return;renderOffset=0;all.clear();all.addAll(merged);sourceMode="M3U";sourceLabel.setText("SOURCES : "+urls.size()+" M3U");
   if(loaded==0){String problem=errors.isEmpty()?"Aucune chaîne reçue. Vérifie tes sources.":"Erreur M3U : "+errors.get(0);status.setText(problem);renderList();onStartupPlaylistLoadFinished(false,"AUCUNE PLAYLIST DISPONIBLE • VÉRIFIE TES SOURCES");return;}
   status.setText(loaded+" chaînes/contenus chargés • "+urls.size()+" source(s) M3U"+(errors.isEmpty()?"":" • "+errors.size()+" erreur(s)"));renderList();onStartupPlaylistLoadFinished(true,loaded+" CHAÎNES CHARGÉES • PRÊT À REGARDER");
  });
 });
}

    private String api(String action,String extra){String base=xtreamBase;if(!base.endsWith("/"))base+="/";return base+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass)+"&action="+action+(extra==null?"":extra);}
    private String enc(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private String validateXtreamAccount(JSONObject user)throws IOException{
        if(user==null||!user.has("auth"))throw new IOException("Réponse d’authentification Xtream invalide");
        if(!"1".equals(user.optString("auth","")))throw new IOException("Identifiants refusés par le serveur");
        String accountStatus=user.optString("status","").trim().toLowerCase(Locale.ROOT);
        if(accountStatus.equals("expired")||accountStatus.equals("disabled")||accountStatus.equals("banned")||accountStatus.equals("inactive"))
            throw new IOException("Abonnement Xtream expiré ou désactivé");
        String expiration=user.optString("exp_date","").trim();
        if(!expiration.isEmpty()&&!expiration.equals("0")&&!expiration.equalsIgnoreCase("null")){
            try{long expiresAt=Long.parseLong(expiration);if(expiresAt>0L&&expiresAt<=System.currentTimeMillis()/1000L)
                throw new IOException("Abonnement Xtream arrivé à expiration");}
            catch(NumberFormatException ignored){/* Certains serveurs ne fournissent pas une date Unix exploitable. */}
        }
        return accountStatus;
    }
    private void loadXtream(){
 final int request=++catalogRequestId;seriesRequestId++;dismissSeriesDialog();
 // Ne jamais laisser le catalogue de la source précédente visible pendant une nouvelle session Xtream.
 renderOffset=0;all.clear();sourceMode="XTREAM";sourceLabel.setText("SOURCE : XTREAM");
 status.setText("Connexion au serveur Xtream Codes…");renderList();
 executeIo(()->{
  ArrayList<MediaEntry> parsed=new ArrayList<>();ArrayList<String> endpointErrors=new ArrayList<>();String err=null;
  try{
   if(request!=catalogRequestId||destroyed)return;
   String auth=get(xtreamBase+(xtreamBase.endsWith("/")?"":"/")+"player_api.php?username="+enc(xtreamUser)+"&password="+enc(xtreamPass));
   if(request!=catalogRequestId||destroyed)return;
   JSONObject root=new JSONObject(auth);JSONObject user=root.optJSONObject("user_info");validateXtreamAccount(user);
   Map<String,String> liveCats=safeCategories("get_live_categories",request,endpointErrors,"Catégories TV");
   if(request!=catalogRequestId||destroyed)return;
   Map<String,String> vodCats=safeCategories("get_vod_categories",request,endpointErrors,"Catégories films");
   if(request!=catalogRequestId||destroyed)return;
   Map<String,String> seriesCats=safeCategories("get_series_categories",request,endpointErrors,"Catégories séries");
   if(request!=catalogRequestId||destroyed)return;
   try{parseXtreamArray(parsed,get(api("get_live_streams","")),"live","m3u8",liveCats,request);}catch(Exception e){if(request==catalogRequestId)endpointErrors.add("TV : "+e.getMessage());}
   if(request!=catalogRequestId||destroyed)return;
   try{parseXtreamArray(parsed,get(api("get_vod_streams","")),"movie","mp4",vodCats,request);}catch(Exception e){if(request==catalogRequestId)endpointErrors.add("Films : "+e.getMessage());}
   if(request!=catalogRequestId||destroyed)return;
   try{parseXtreamArray(parsed,get(api("get_series","")),"series","mp4",seriesCats,request);}catch(Exception e){if(request==catalogRequestId)endpointErrors.add("Séries : "+e.getMessage());}
   if(request!=catalogRequestId||destroyed)return;
   if(parsed.isEmpty()&&!endpointErrors.isEmpty())throw new IOException(endpointErrors.get(0));
  }catch(Exception e){if(request!=catalogRequestId||destroyed)return;err=e.getMessage();}
  final String error=err;final ArrayList<String> failures=endpointErrors;if(request!=catalogRequestId||destroyed)return;
  postUi(()->{if(request!=catalogRequestId)return;if(error!=null){status.setText("Connexion Xtream impossible : "+error);return;}
   renderOffset=0;all.clear();all.addAll(parsed);sourceMode="XTREAM";sourceLabel.setText("SOURCE : XTREAM");
   status.setText(parsed.size()+" éléments récupérés • Serveur Xtream"+(failures.isEmpty()?"":" • "+failures.size()+" endpoint(s) partiellement indisponible(s)"));renderList();
  });
 });
}
    private Map<String,String> safeCategories(String action,int request,ArrayList<String> errors,String label){try{if(request!=catalogRequestId||destroyed)return new HashMap<>();String json=get(api(action,""));if(request!=catalogRequestId||destroyed)return new HashMap<>();return parseCategories(json,request);}catch(Exception e){if(request==catalogRequestId&&!destroyed)errors.add(label+" : "+(e.getMessage()==null?"réponse invalide":e.getMessage()));return new HashMap<>();}}
    private Map<String,String> parseCategories(String json,int request)throws Exception{Map<String,String> map=new HashMap<>();JSONArray a;try{a=new JSONArray(json);}catch(Exception e){throw new IOException("Réponse catégories Xtream invalide",e);}for(int i=0;i<a.length()&&request==catalogRequestId&&!destroyed;i++){JSONObject o=a.optJSONObject(i);if(o!=null){String id=o.optString("category_id","");if(!id.isEmpty())map.put(id,o.optString("category_name","Autres"));}}return map;}
    private boolean isSafeXtreamId(String id){return id!=null&&id.matches("[0-9]{1,20}");}
    private void parseXtreamArray(ArrayList<MediaEntry> out,String json,String type,String fallback,Map<String,String> categories,int request)throws Exception{JSONArray a;try{a=new JSONArray(json);}catch(Exception e){throw new IOException("Réponse "+(type.equals("live")?"TV":type.equals("movie")?"films":"séries")+" Xtream invalide",e);}for(int i=0;i<a.length()&&out.size()<MAX_CATALOG_ITEMS&&request==catalogRequestId&&!destroyed;i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString(type.equals("series")?"series_id":"stream_id",o.optString("id",""));if(!isSafeXtreamId(id))continue;String name=o.optString("name",type.equals("series")?"Série":"Contenu");String cat=o.optString("category_name",(categories.containsKey(o.optString("category_id"))?categories.get(o.optString("category_id")):(type.equals("live")?"TV en direct":type.equals("movie")?"Films":"Séries")));String logo=o.optString("stream_icon",o.optString("cover",""));String ext=o.optString("container_extension",fallback);if(!ext.matches("[A-Za-z0-9]{1,8}"))ext=fallback;String path=xtreamBase+(xtreamBase.endsWith("/")?"":"/")+type+"/"+enc(xtreamUser)+"/"+enc(xtreamPass)+"/"+id+"."+ext;MediaEntry m=new MediaEntry(name,path,cat,logo,type);m.id=id;m.container=ext;m.tvgId=o.optString("epg_channel_id","");out.add(m);}}

    private boolean hasHistory(MediaEntry c){if(c==null||c.url==null||c.url.isEmpty())return false;try{return historyKeysCache.contains("history_"+key(c.url));}catch(Exception ex){return false;}}
    private long historyPosition(MediaEntry c){if(c==null||c.url==null||prefs==null)return 0L;try{Object value=prefs.getAll().get("position_"+key(c.url));return value instanceof Long?Math.max(0L,(Long)value):0L;}catch(Exception ex){return 0L;}}
    private boolean matchSection(MediaEntry c){
        if(c==null||c.url==null)return false;
        if(section.equals("MTV")) return mtvItems.contains(c);
        if(section.equals("MUSIQUE"))return isMusicEntry(c);
        if(section.equals("INFOS"))return isNewsEntry(c);if(section.equals("SPORTS"))return isSportsEntry(c);if(section.equals("DIVERTISSEMENT"))return isEntertainmentEntry(c);if(section.equals("MAGAZINE"))return isMagazineEntry(c);if(section.equals("JEUNESSE"))return isYouthEntry(c);if(section.equals("ÉDUCATION"))return isEducationEntry(c);
        String g=c.group==null?"":c.group.toLowerCase(Locale.ROOT),n=c.name==null?"":c.name.toLowerCase(Locale.ROOT);if(section.equals("FAVORIS")){if(!favorites.contains(c.url))return false;if(activeFilter!=null&&!activeFilter.equals("Tout")){Set<String> group=favoriteGroups.get(activeFilter);return group!=null&&group.contains(c.url);}return true;}if(section.equals("HISTORIQUE"))return hasHistory(c);if(section.equals("GUIDE TV"))return (c.type.equals("live")||c.type.equals("m3u"))&&!c.tvgId.isEmpty();if(section.equals("TV EN DIRECT"))return c.type.equals("live")||c.type.equals("m3u");if(section.equals("FILMS"))return c.type.equals("movie")||g.contains("movie")||g.contains("film")||g.contains("vod");if(section.equals("SÉRIES"))return c.type.equals("series")||g.contains("series");if(section.equals("ANIMÉS"))return g.contains("anime")||g.contains("animation")||g.contains("cartoon")||n.contains("anime");return true;}
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
    private boolean isWidePlayerLayout(){
        android.content.pm.PackageManager manager=getPackageManager();
        boolean television=manager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            ||manager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION);
        return television||getResources().getConfiguration().screenWidthDp>=720;
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

        LinearLayout hero=column();GradientDrawable heroBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF33205F,0xFF154A61,0xFF10243B});heroBg.setCornerRadius(dp(18));hero.setBackground(heroBg);hero.setPadding(dp(22),dp(16),dp(18),dp(16));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(128));hp.topMargin=dp(12);hp.bottomMargin=dp(14);list.addView(hero,hp);
        TextView heroTag=text("À LA UNE  •  MTv",10,0xFFFFD36A,true);hero.addView(heroTag,new LinearLayout.LayoutParams(-1,dp(22)));
        TextView heroTitle=text("Vos chaînes et programmes préférés",20,Color.WHITE,true);hero.addView(heroTitle,new LinearLayout.LayoutParams(-1,dp(38)));
        TextView heroDetail=text(all.size()+" contenus disponibles  •  "+playlistSources.size()+" source(s) M3U",12,0xFFE0E8F5,false);hero.addView(heroDetail,new LinearLayout.LayoutParams(-1,dp(24)));
        TextView catTitle=text("EXPLORER LES RUBRIQUES",12,0xFFFFC45C,true);catTitle.setLetterSpacing(0.08f);list.addView(catTitle,new LinearLayout.LayoutParams(-1,dp(28)));
        HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);LinearLayout categoryRow=row();categoryScroll.addView(categoryRow);list.addView(categoryScroll,new LinearLayout.LayoutParams(-1,dp(54)));
        String[][] quick={{"TV EN DIRECT","TV EN DIRECT"},{"SPORTS","SPORTS"},{"DIVERTISSEMENT","DIVERTISSEMENT"},{"MAGAZINE","MAGAZINE"},{"JEUNESSE","JEUNESSE"},{"ÉDUCATION","ÉDUCATION"},{"FILMS","FILMS"},{"SÉRIES","SÉRIES"},{"ANIMÉS","ANIMÉS"},{"MUSIQUE","MUSIQUE"},{"GUIDE TV","GUIDE TV"}};
        for(String[] item:quick){TextView tile=text(item[0],12,Color.WHITE,true);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(16),0,dp(16),0);tile.setFocusable(true);tile.setClickable(true);tile.setBackground(bg(CARD,12));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,dp(40));tp.rightMargin=dp(9);categoryRow.addView(tile,tp);tile.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(FOCUS_GOLD,12);d.setStroke(dp(2),FOCUS_RED);v.setBackground(d);((TextView)v).setTextColor(0xFF17111A);v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110).start();}else{v.setBackground(bg(CARD,12));((TextView)v).setTextColor(Color.WHITE);v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});tile.setOnClickListener(v->{navigateTo(item[1]);});}
        TextView featuredTitle=text("CHAÎNES À DÉCOUVRIR",12,0xFFFFC45C,true);featuredTitle.setLetterSpacing(0.08f);LinearLayout.LayoutParams ft=new LinearLayout.LayoutParams(-1,dp(34));ft.topMargin=dp(8);list.addView(featuredTitle,ft);
        HorizontalScrollView featuredScroll=new HorizontalScrollView(this);featuredScroll.setHorizontalScrollBarEnabled(false);LinearLayout featured=row();featuredScroll.addView(featured);list.addView(featuredScroll,new LinearLayout.LayoutParams(-1,dp(154)));
        int count=0;for(MediaEntry entry:all){if(entry==null||entry.name==null||entry.name.isEmpty())continue;if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(entry))continue;LinearLayout card=column();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(dp(8),dp(8),dp(8),dp(6));card.setBackground(bg(PANEL,12));card.setFocusable(true);card.setClickable(true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(132),dp(138));cp.rightMargin=dp(10);featured.addView(card,cp);
            ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackground(bg(CARD,8));card.addView(logo,new LinearLayout.LayoutParams(dp(112),dp(78)));if(entry.logo!=null&&!entry.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(entry.logo).override(dp(112),dp(78)).placeholder(bg(CARD,8)).error(bg(CARD,8)).into(logo);
            TextView label=text(entry.name,11,Color.WHITE,true);label.setGravity(Gravity.CENTER);label.setMaxLines(2);card.addView(label,new LinearLayout.LayoutParams(-1,dp(42)));
            card.setOnFocusChangeListener((v,focus)->{if(focus){GradientDrawable d=bg(0xFF3A315D,12);d.setStroke(dp(2),FOCUS_GOLD);v.setBackground(d);v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(110).start();}else{v.setBackground(bg(PANEL,12));v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();}});
            card.setOnClickListener(v->play(entry));if(++count>=12)break;
        }
        if(count==0){TextView empty=text("Les chaînes apparaîtront ici dès que la playlist sera chargée.",12,MUTED,false);list.addView(empty);}
        TextView hint=text("Astuce : utilise les flèches de la télécommande pour parcourir les cartes, puis OK pour ouvrir.",11,MUTED,false);LinearLayout.LayoutParams hintP=new LinearLayout.LayoutParams(-1,dp(34));hintP.topMargin=dp(8);list.addView(hint,hintP);
    }
    private void renderList(){
        if(list==null)return;
        list.removeAllViews();
        if(section.equals("HISTORIQUE")){
            historyKeysCache.clear();
            try{for(String prefKey:prefs.getAll().keySet())if(prefKey.startsWith("history_"))historyKeysCache.add(prefKey);}catch(Exception ignored){}
        }
        String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);
        if(section.equals("ACCUEIL")&&q.trim().isEmpty()){renderHome();return;}
        int shown=0,matched=0;
        boolean hasMore=false;
        List<MediaEntry> activeItems=section.equals("MTV")?mtvItems:all;
        Set<String> displayedHistoryUrls=new HashSet<>();
        for(MediaEntry c:activeItems){
            if(!matchSection(c))continue;
            if(section.equals("HISTORIQUE")&&!displayedHistoryUrls.add(c.url))continue;
            if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(c))continue;
            String itemName=c.name==null?"":c.name;
            String itemGroup=c.group==null?"":c.group;
            if(!q.isEmpty()&&!itemName.toLowerCase(Locale.ROOT).contains(q)&&!itemGroup.toLowerCase(Locale.ROOT).contains(q))continue;
            if(!filterMatches(activeFilter,itemGroup,itemName))continue;
            if(matched++<renderOffset)continue;
            if(shown>=maxRenderItems){hasMore=true;break;}
            LinearLayout item=row();item.setId(View.generateViewId());item.setPadding(dp(13),dp(4),dp(12),dp(4));item.setBackground(bg(PANEL,12));item.setFocusable(true);item.setClickable(true);
            item.setBackground(bg(0xFF17233A,12));
            item.setOnFocusChangeListener((view,hasFocus)->view.setBackground(bg(hasFocus?ACCENT:0xFF17233A,12)));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62));ip.bottomMargin=dp(8);list.addView(item,ip);
            if(chips!=null){for(int ci=0;ci<chips.getChildCount();ci++)chips.getChildAt(ci).setNextFocusDownId(item.getId());if(chips.getChildCount()>0)item.setNextFocusUpId(chips.getChildAt(0).getId());}
            ImageView artwork=new ImageView(this);artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.setBackground(bg(CARD,8));item.addView(artwork,new LinearLayout.LayoutParams(dp(76),dp(52)));
            if(c.logo!=null&&!c.logo.isEmpty())com.bumptech.glide.Glide.with(this).load(c.logo).override(dp(76),dp(52)).placeholder(bg(CARD,8)).error(bg(CARD,8)).into(artwork);else artwork.setImageDrawable(bg(CARD,8));
            LinearLayout meta=column();meta.setPadding(dp(12),0,0,0);item.addView(meta,new LinearLayout.LayoutParams(0,-1,1));
            meta.addView(text(itemName,14,Color.WHITE,true));
            String detail=itemGroup+"  •  "+(c.type.equals("live")||c.type.equals("m3u")?"En direct":c.type.equals("series")?"Série":"VOD");
            if(!c.tvgId.isEmpty()&&epgNow.containsKey(c.tvgId))detail+="  •  En cours : "+epgNow.get(c.tvgId);
            if(!c.tvgId.isEmpty()&&epgNext.containsKey(c.tvgId))detail+="  •  Ensuite : "+epgNext.get(c.tvgId);
            if(section.equals("HISTORIQUE")){long pos=historyPosition(c);detail+=pos>0L?"  •  Reprendre à "+(pos/60000)+" min":"  •  Vu précédemment";}
            meta.addView(text(detail,10,MUTED,false));
            TextView fav=text(favorites.contains(c.url)?"♥":"♡",22,0xFFFF7199,true);fav.setGravity(Gravity.CENTER);fav.setId(View.generateViewId());fav.setFocusable(true);fav.setClickable(true);fav.setBackground(bg(CARD,8));fav.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?ACCENT:CARD,8)));
            item.setNextFocusRightId(fav.getId());item.addView(fav,new LinearLayout.LayoutParams(dp(52),-1));fav.setNextFocusLeftId(item.getId());
            fav.setOnClickListener(v->{if(!favorites.add(c.url))favorites.remove(c.url);putSecureStringSet("favorites",new HashSet<>(favorites));fav.setText(favorites.contains(c.url)?"♥":"♡");renderList();});
            fav.setOnLongClickListener(v->{showAssignFavoriteGroup(c);return true;});
            item.setOnClickListener(v->{if(c.type.equals("series"))showSeries(c);else play(c);});
            shown++;
        }
        if(hasMore||renderOffset>0){
            LinearLayout pages=row();pages.setGravity(Gravity.CENTER);pages.setPadding(dp(4),dp(6),dp(4),dp(10));list.addView(pages,new LinearLayout.LayoutParams(-1,dp(58)));
            if(renderOffset>0){
                TextView previous=text("‹  Précédents",13,Color.WHITE,true);previous.setGravity(Gravity.CENTER);previous.setFocusable(true);previous.setClickable(true);previous.setPadding(dp(12),dp(8),dp(12),dp(8));previous.setBackground(bg(CARD,10));pages.addView(previous,new LinearLayout.LayoutParams(0,dp(44),1));
                previous.setOnClickListener(v->{renderOffset=Math.max(0,renderOffset-maxRenderItems);renderList();if(list.getChildCount()>0)list.getChildAt(0).requestFocus();});
            }
            if(hasMore){
                TextView next=text("Suivants  ›",13,Color.WHITE,true);next.setGravity(Gravity.CENTER);next.setFocusable(true);next.setClickable(true);next.setPadding(dp(12),dp(8),dp(12),dp(8));next.setBackground(bg(ACCENT,10));
                LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(44),1);if(renderOffset>0)np.leftMargin=dp(8);pages.addView(next,np);
                next.setOnClickListener(v->{renderOffset+=maxRenderItems;renderList();if(list.getChildCount()>0)list.getChildAt(0).requestFocus();});
            }
        }
        if(shown==0){
            TextView empty=text(section.equals("MTV")?(mtvPlaylistUrl.isEmpty()?"Configure une playlist M3U autorisée pour MTV dans Paramètres.":"Aucune chaîne MTV disponible dans cette source."):(all.isEmpty()?"Aucun contenu chargé. Configure une source M3U ou Xtream dans Paramètres.":"Aucun résultat dans cette rubrique."),14,MUTED,false);
            empty.setPadding(dp(12),dp(30),dp(12),dp(20));list.addView(empty);
        }
    }
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
        if(section.equals("INFOS"))return isNewsEntry(entry);if(section.equals("SPORTS"))return isSportsEntry(entry);if(section.equals("DIVERTISSEMENT"))return isEntertainmentEntry(entry);if(section.equals("MAGAZINE"))return isMagazineEntry(entry);if(section.equals("JEUNESSE"))return isYouthEntry(entry);if(section.equals("ÉDUCATION"))return isEducationEntry(entry);
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
     private boolean matchesMediaKeywords(MediaEntry e,String[] keys){if(e==null)return false;String v=((e.name==null?"":e.name)+" "+(e.group==null?"":e.group)).toLowerCase(Locale.ROOT);for(String k:keys)if(v.contains(k))return true;return false;}
     private boolean isSportsEntry(MediaEntry e){return matchesMediaKeywords(e,new String[]{"sport","sports","football","soccer","rugby","basketball","basket","tennis","formula 1","formula1","f1","motogp","boxing","boxe","ufc","mma","golf","cricket","eurosport","bein sport","espn","sky sports","sport tv"});}
     private boolean isEntertainmentEntry(MediaEntry e){return matchesMediaKeywords(e,new String[]{"divertissement","entertainment","variety","variété","variete","reality","téléréalité","telerealite","show","comedy","comedie","comédie","humour","game show","talk show"});}
     private boolean isMagazineEntry(MediaEntry e){return matchesMediaKeywords(e,new String[]{"magazine","magazines","talk","débat","debat","lifestyle","people","society","société","societe","actualité magazine"});}
     private boolean isYouthEntry(MediaEntry e){return matchesMediaKeywords(e,new String[]{"jeunesse","youth","kids","kidzone","children","childrens","junior","enfant","enfants","family","famille","preschool","maternelle"});}
     private boolean isEducationEntry(MediaEntry e){return matchesMediaKeywords(e,new String[]{"education","éducation","educational","éducatif","educatif","school","école","ecole","learning","apprentissage","formation","science","savoir","knowledge","université","universite"});}
    private void startBufferingWatchdog(ExoPlayer activePlayer,MediaEntry fallback){
        stopBufferingWatchdog(null);
        if(activePlayer==null||destroyed||player!=activePlayer)return;
        try{
            if(!activePlayer.getPlayWhenReady())return;
        }catch(RuntimeException releasedPlayer){
            android.util.Log.w("GLOBALTV","Watchdog ignoré : lecteur déjà libéré",releasedPlayer);
            return;
        }
        bufferingWatchdogPlayer=activePlayer;
        bufferingWatchdog=()->{
            if(destroyed||player!=activePlayer){stopBufferingWatchdog(activePlayer);return;}
            try{
                if(activePlayer.getPlaybackState()==Player.STATE_BUFFERING&&activePlayer.getPlayWhenReady()){
                    bufferingWatchdog=null;bufferingWatchdogPlayer=null;
                    handlePlayerFailure(activePlayer,fallback);
                }else{
                    stopBufferingWatchdog(activePlayer);
                }
            }catch(RuntimeException releasedPlayer){
                android.util.Log.w("GLOBALTV","Watchdog arrêté après libération du lecteur",releasedPlayer);
                stopBufferingWatchdog(activePlayer);
            }
        };
        mainHandler.postDelayed(bufferingWatchdog,45000L);
    }
    private boolean hasInternetConnection(){
        try{
            if(connectivityManager==null)connectivityManager=(android.net.ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivityManager==null)return false;
            android.net.Network active=connectivityManager.getActiveNetwork();
            if(active==null)return false;
            android.net.NetworkCapabilities caps=connectivityManager.getNetworkCapabilities(active);
            return caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }catch(Exception ignored){return false;}
    }
    private void registerNetworkMonitor(){
        if(networkCallbackRegistered)return;
        try{
            if(connectivityManager==null)connectivityManager=(android.net.ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivityManager==null)return;
            networkUnavailable=!hasInternetConnection();
            android.net.NetworkRequest request=new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            if(networkCallback==null)networkCallback=new android.net.ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(android.net.Network network){mainHandler.post(()->handleNetworkAvailable());}
                @Override public void onLost(android.net.Network network){mainHandler.post(()->{if(!hasInternetConnection())networkUnavailable=true;});}
            };
            connectivityManager.registerNetworkCallback(request,networkCallback);
            networkCallbackRegistered=true;
        }catch(Exception ignored){networkCallbackRegistered=false;}
    }
    private void handleNetworkAvailable(){
        if(destroyed||!hasInternetConnection()){networkUnavailable=true;return;}
        if(!networkUnavailable)return;
        networkUnavailable=false;
        if(networkRecoveryRunnable!=null)mainHandler.removeCallbacks(networkRecoveryRunnable);
        final ExoPlayer expectedPlayer=player;
        if(expectedPlayer==null)return;
        networkRecoveryRunnable=()->{
            networkRecoveryRunnable=null;
            if(destroyed||player!=expectedPlayer)return;
            try{
                if(!expectedPlayer.getPlayWhenReady())return;
                int state=expectedPlayer.getPlaybackState();
                if(state==Player.STATE_BUFFERING||state==Player.STATE_IDLE){
                    expectedPlayer.prepare();
                    expectedPlayer.play();
                    if(!destroyed&&player==expectedPlayer&&expectedPlayer.getPlaybackState()==Player.STATE_BUFFERING)
                        startBufferingWatchdog(expectedPlayer,currentEntry);
                }
            }catch(RuntimeException recoveryError){
                android.util.Log.e("GLOBALTV","Échec de la reprise après retour du réseau",recoveryError);
                if(player==expectedPlayer){
                    try{expectedPlayer.pause();}catch(RuntimeException ignored){}
                    if(status!=null)status.setText("Réseau rétabli, mais le lecteur n’a pas pu reprendre. Relance la chaîne.");
                }
            }
        };
        mainHandler.postDelayed(networkRecoveryRunnable,1500L);
    }
    private void unregisterNetworkMonitor(){
        if(networkRecoveryRunnable!=null)mainHandler.removeCallbacks(networkRecoveryRunnable);
        networkRecoveryRunnable=null;
        if(networkCallbackRegistered&&connectivityManager!=null&&networkCallback!=null){
            try{connectivityManager.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        }
        networkCallbackRegistered=false;
    }
    private void stopBufferingWatchdog(ExoPlayer expected){
        if(expected!=null&&bufferingWatchdogPlayer!=expected)return;
        if(bufferingWatchdog!=null)mainHandler.removeCallbacks(bufferingWatchdog);
        bufferingWatchdog=null;bufferingWatchdogPlayer=null;
    }
    private int findHttpStatusCode(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){
                return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)cause).responseCode;
            }
        }
        return -1;
    }

    private boolean isRetryablePlaybackError(androidx.media3.common.PlaybackException error){
        if(error==null)return true; // Expiration du watchdog de buffering.
        int httpCode=findHttpStatusCode(error);
        if(httpCode!=-1)return httpCode==408||httpCode==425||httpCode==429||httpCode>=500&&httpCode<=599;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof java.net.SocketTimeoutException||
               cause instanceof java.net.UnknownHostException||
               cause instanceof java.net.ConnectException||
               cause instanceof java.net.NoRouteToHostException||
               cause instanceof java.net.SocketException||
               cause instanceof java.io.InterruptedIOException)return true;
        }
        return false;
    }

    private String playbackFailureMessage(androidx.media3.common.PlaybackException error){
        int code=findHttpStatusCode(error);
        if(code==401||code==403)return "Le fournisseur refuse cette requête (HTTP "+code+"). Vérifiez les droits du compte et les en-têtes exigés par votre fournisseur.";
        if(code==404||code==410)return "Ce flux n’existe plus ou a été retiré (HTTP "+code+").";
        if(code==429)return "Le fournisseur limite temporairement les connexions (HTTP 429).";
        if(code>=500&&code<=599)return "Le serveur du fournisseur rencontre un problème (HTTP "+code+").";
        if(code==408)return "Le fournisseur a mis trop de temps à répondre (HTTP 408).";
        return "Le flux est invalide ou incompatible avec le lecteur.";
    }

    private void clearPlaybackRecovery(ExoPlayer expected){
        if(expected!=null&&playbackRecoveryPlayer!=expected)return;
        if(playbackRecoveryRunnable!=null)mainHandler.removeCallbacks(playbackRecoveryRunnable);
        playbackRecoveryRunnable=null;playbackRecoveryPlayer=null;
    }

    private void handlePlayerFailure(ExoPlayer activePlayer,MediaEntry fallback){
        handlePlayerFailure(activePlayer,fallback,null);
    }

    private void handlePlayerFailure(ExoPlayer activePlayer,MediaEntry fallback,androidx.media3.common.PlaybackException error){
        if(activePlayer==null||player!=activePlayer||playbackRecoveryPlayer==activePlayer)return;
        playbackRecoveryPlayer=activePlayer;
        MediaItem playing=activePlayer.getCurrentMediaItem();
        String failedUrl=playing!=null&&playing.localConfiguration!=null
            ?playing.localConfiguration.uri.toString()
            :(currentEntry!=null?currentEntry.url:(fallback==null?"":fallback.url));
        boolean retryable=isRetryablePlaybackError(error);
        boolean firstFailure=retryable&&!failedUrl.isEmpty()&&failedInCurrentRun.add(failedUrl);
        postUi(()->{
            if(player!=activePlayer){clearPlaybackRecovery(activePlayer);return;}
            stopBufferingWatchdog(activePlayer);
            if(retryable&&!hasInternetConnection()){
                networkUnavailable=true;
                status.setText("Connexion Internet interrompue. La lecture reprendra automatiquement au retour du réseau.");
                clearPlaybackRecovery(activePlayer);
                return;
            }
            if(firstFailure){
                status.setText("Flux interrompu. Nouvelle tentative…");
                playbackRecoveryRunnable=()->{
                    playbackRecoveryRunnable=null;
                    if(player!=activePlayer){clearPlaybackRecovery(activePlayer);return;}
                    try{
                        activePlayer.prepare();
                        activePlayer.play();
                    }catch(RuntimeException recoveryError){
                        android.util.Log.e("GLOBALTV","Échec de la première reprise Media3",recoveryError);
                        try{activePlayer.pause();}catch(RuntimeException ignored){}
                        status.setText("Reprise impossible pour ce flux. Passage au suivant…");
                    }finally{clearPlaybackRecovery(activePlayer);}
                };
                mainHandler.postDelayed(playbackRecoveryRunnable,650L);
                return;
            }

            int failedIndex=activePlayer.getCurrentMediaItemIndex();
            int itemCount=activePlayer.getMediaItemCount();
            if(failedIndex<0||failedIndex>=itemCount){
                try{activePlayer.pause();}catch(RuntimeException ignored){}
                status.setText("Flux indisponible. Sélectionne une autre chaîne.");
                clearPlaybackRecovery(activePlayer);
                return;
            }
            try{
                activePlayer.removeMediaItem(failedIndex);
                int remaining=activePlayer.getMediaItemCount();
                if(remaining<=0){
                    activePlayer.pause();
                    status.setText("Aucun autre flux disponible dans cette file.");
                    String detail=playbackFailureMessage(error);
                    Toast.makeText(MainActivity.this,detail,Toast.LENGTH_LONG).show();
                    clearPlaybackRecovery(activePlayer);
                    return;
                }
                int httpCode=findHttpStatusCode(error);
                if(httpCode==401||httpCode==403)
                    status.setText("Flux refusé (HTTP "+httpCode+"). Passage au suivant…");
                else
                    Toast.makeText(MainActivity.this,(retryable?"Flux indisponible après une nouvelle tentative. ":"")+playbackFailureMessage(error)+" Passage au suivant…",Toast.LENGTH_SHORT).show();

                playbackRecoveryRunnable=()->{
                    playbackRecoveryRunnable=null;
                    if(player!=activePlayer){clearPlaybackRecovery(activePlayer);return;}
                    try{
                        int count=activePlayer.getMediaItemCount();
                        if(count<=0){activePlayer.pause();status.setText("Aucun autre flux disponible.");return;}
                        int nextIndex=Math.min(failedIndex,count-1);
                        activePlayer.seekTo(nextIndex,0L);
                        activePlayer.prepare();
                        activePlayer.play();
                    }catch(RuntimeException recoveryError){
                        android.util.Log.e("GLOBALTV","Échec du passage au flux suivant",recoveryError);
                        try{activePlayer.pause();}catch(RuntimeException ignored){}
                        status.setText("Impossible de relancer le flux suivant.");
                    }finally{clearPlaybackRecovery(activePlayer);}
                };
                mainHandler.postDelayed(playbackRecoveryRunnable,350L);
            }catch(RuntimeException recoveryError){
                android.util.Log.e("GLOBALTV","Erreur pendant la récupération du flux",recoveryError);
                try{activePlayer.pause();}catch(RuntimeException ignored){}
                status.setText("Le flux est indisponible. La lecture a été arrêtée sans fermer l’application.");
                clearPlaybackRecovery(activePlayer);
            }
        });
    }

    private void startPlayer(MediaEntry c){
        if(c==null||c.url==null||!(c.url.startsWith("https://")||c.url.startsWith("http://"))){
            Toast.makeText(this,"Adresse du flux invalide",Toast.LENGTH_LONG).show();return;
        }
        if(PROFILE_CHILD.equals(activeProfile)&&isAdultEntry(c)){
            Toast.makeText(this,"Ce contenu est bloqué dans le profil Enfant.",Toast.LENGTH_LONG).show();return;
        }
        try{
            savePlaybackPosition();releasePlayer();currentEntry=c;
            player=new ExoPlayer.Builder(getApplicationContext()).build();
            playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);playerView.setBackgroundColor(Color.BLACK);playerView.setKeepScreenOn(true);final PlayerView activePlayerView=playerView;
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
            boolean wideLayout=isWidePlayerLayout();
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
            lastPlayedUrl=c.url;long resumePosition=historyPosition(c);pendingResumePosition=0L;
            historyKeysCache.add("history_"+key(c.url));prefs.edit().putString("history_"+key(c.url),c.name==null?"":c.name).apply();
            List<MediaEntry> queue=playableQueue(c);ConcatenatingMediaSource playlistSource=new ConcatenatingMediaSource();int startIndex=0;
            for(int i=0;i<queue.size();i++){
                MediaEntry entry=queue.get(i);
                MediaItem mediaItem=new MediaItem.Builder().setUri(Uri.parse(entry.url)).setMediaId(entry.url).build();
                DefaultHttpDataSource.Factory httpFactory=new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true);
                if(!entry.httpHeaders.isEmpty())httpFactory.setDefaultRequestProperties(new HashMap<>(entry.httpHeaders));
                MediaSource mediaSource=new DefaultMediaSourceFactory(httpFactory).createMediaSource(mediaItem);
                playlistSource.addMediaSource(mediaSource);
                if(entry.url.equals(c.url))startIndex=i;
            }
            activePlayer.addListener(new Player.Listener(){
                @Override public void onPlayWhenReadyChanged(boolean playWhenReady,int reason){activePlayerView.setKeepScreenOn(playWhenReady);}
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    handlePlayerFailure(activePlayer,c,e);
                }
                @Override public void onPlaybackStateChanged(int playbackState){
                    if(player!=activePlayer)return;
                    if(playbackState==Player.STATE_BUFFERING)startBufferingWatchdog(activePlayer,c);
                    else stopBufferingWatchdog(activePlayer);
                    if(playbackState==Player.STATE_ENDED){stopPlaybackPositionSaver();clearPlaybackPosition(currentEntry);}
                }
                @Override public void onMediaItemTransition(MediaItem item,int reason){
                    if(player!=activePlayer)return;
                    if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)clearPlaybackPosition(currentEntry);
                    String uri=item.localConfiguration==null?"":item.localConfiguration.uri.toString();
                    for(MediaEntry entry:queue)if(entry.url.equals(uri)){
                        currentEntry=entry;lastPlayedUrl=entry.url;
                        historyKeysCache.add("history_"+key(entry.url));prefs.edit().putString("history_"+key(entry.url),entry.name==null?"":entry.name).apply();
                        break;
                    }
                }
            });
            activePlayer.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
            activePlayer.setMediaSource(playlistSource);activePlayer.seekTo(startIndex,resumePosition);activePlayer.prepare();activePlayer.play();
            status.setText("Lecture : "+c.name);
            applyRestoredPlayerState();
            startPlaybackPositionSaver(activePlayer);
        }catch(Exception e){releasePlayer();Toast.makeText(this,"Impossible de lancer ce flux",Toast.LENGTH_LONG).show();}
    }

    private void applyRestoredPlayerState(){
        if(!pendingPlayerStateRestore||player==null||playerView==null)return;
        if(!restorePlaybackWasPlaying)player.pause();
        if(restorePlayerFullscreen&&!playerFullscreen)togglePlayerExpanded();
        pendingPlayerStateRestore=false;
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
        View videoColumn=playerView.getParent() instanceof ViewGroup? (View)playerView.getParent():null;
        boolean wideLayout=isWidePlayerLayout();
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
        applyImmersiveMode();playerView.requestFocus();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&playerFullscreen)applyImmersiveMode();}
    private void applyImmersiveMode(){
        WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsetsCompat.Type.systemBars());}
    }

    private int seasonOrder(String value){
        if(value==null)return Integer.MAX_VALUE;
        try{return Integer.parseInt(value.trim());}
        catch(Exception ignored){return Integer.MAX_VALUE;}
    }

    private void dismissSeriesDialog(){
        if(seriesDialog!=null){
            AlertDialog old=seriesDialog;seriesDialog=null;
            if(old.isShowing())old.dismiss();
        }
    }
    private void showSeries(MediaEntry s){
        if(!sourceMode.equals("XTREAM")){
            Toast.makeText(this,"Les épisodes doivent être fournis par une source VOD compatible.",Toast.LENGTH_LONG).show();return;
        }
        if(s==null||!isSafeXtreamId(s.id)){
            Toast.makeText(this,"Identifiant de série invalide.",Toast.LENGTH_LONG).show();return;
        }
        final int request=++seriesRequestId;
        final String sessionBase=xtreamBase,sessionUser=xtreamUser,sessionPass=xtreamPass;
        dismissSeriesDialog();
        status.setText("Chargement des saisons…");
        executeIo(()->{
            String err=null;ArrayList<MediaEntry> eps=new ArrayList<>();
            try{
                String base=sessionBase+(sessionBase.endsWith("/")?"":"/");
                String response=get(base+"player_api.php?username="+enc(sessionUser)+"&password="+enc(sessionPass)+"&action=get_series_info&series_id="+enc(s.id));
                if(request!=seriesRequestId||destroyed)return;
                JSONObject data=new JSONObject(response);
                JSONObject seasons=data.optJSONObject("episodes");
                if(seasons==null)throw new IOException("Réponse des saisons Xtream invalide ou indisponible");
                ArrayList<String> seasonKeys=new ArrayList<>();
                Iterator<String> seasonIterator=seasons.keys();
                while(seasonIterator.hasNext())seasonKeys.add(seasonIterator.next());
                Collections.sort(seasonKeys,(a,b)->{
                    int na=seasonOrder(a),nb=seasonOrder(b);
                    if(na!=Integer.MAX_VALUE&&nb!=Integer.MAX_VALUE)return Integer.compare(na,nb);
                    if(na!=Integer.MAX_VALUE)return -1;
                    if(nb!=Integer.MAX_VALUE)return 1;
                    return a.compareToIgnoreCase(b);
                });
                for(String season:seasonKeys){
                    if(eps.size()>=2000||request!=seriesRequestId||destroyed)break;
                    JSONArray episodes=seasons.optJSONArray(season);
                    if(episodes==null)continue;
                    ArrayList<JSONObject> orderedEpisodes=new ArrayList<>();
                    for(int i=0;i<episodes.length();i++){
                        JSONObject episode=episodes.optJSONObject(i);
                        if(episode!=null)orderedEpisodes.add(episode);
                    }
                    Collections.sort(orderedEpisodes,(a,b)->{
                        int ea=a.optInt("episode_num",Integer.MAX_VALUE);
                        int eb=b.optInt("episode_num",Integer.MAX_VALUE);
                        return Integer.compare(ea,eb);
                    });
                    for(int i=0;i<orderedEpisodes.size()&&eps.size()<2000&&request==seriesRequestId&&!destroyed;i++){
                        JSONObject episode=orderedEpisodes.get(i);
                        String id=episode.optString("id","");
                        if(!isSafeXtreamId(id))continue;
                        String ext=episode.optString("container_extension","mp4");
                        if(!ext.matches("[A-Za-z0-9]{1,8}"))ext="mp4";
                        String url=base+"series/"+enc(sessionUser)+"/"+enc(sessionPass)+"/"+id+"."+ext;
                        String seriesLabel=s.name==null?"Série":s.name.trim();
                        String episodeGroup=(seriesLabel.isEmpty()?"Série":seriesLabel)+" • Saison "+season;
                        eps.add(new MediaEntry(episode.optString("title","Épisode "+(i+1)),url,episodeGroup,"","episode"));
                    }
                }
            }catch(Exception e){if(request!=seriesRequestId||destroyed)return;err=e.getMessage();}
            if(request!=seriesRequestId||destroyed)return;
            final String error=err;
            postUi(()->{
                if(request!=seriesRequestId||destroyed||!sourceMode.equals("XTREAM")||
                    !sessionBase.equals(xtreamBase)||!sessionUser.equals(xtreamUser)||!sessionPass.equals(xtreamPass))return;
                if(error!=null||eps.isEmpty()){
                    Toast.makeText(this,error==null?"Aucun épisode disponible":("Séries : "+error),Toast.LENGTH_LONG).show();return;
                }
                String[] names=new String[eps.size()];
                for(int i=0;i<eps.size();i++)names[i]=eps.get(i).group+" — "+eps.get(i).name;
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle(s.name)
                    .setItems(names,(d,w)->{
                        if(request==seriesRequestId&&sourceMode.equals("XTREAM")&&
                            sessionBase.equals(xtreamBase)&&sessionUser.equals(xtreamUser)&&sessionPass.equals(xtreamPass))
                            play(eps.get(w));
                    }).setNegativeButton("Fermer",null).create();
                seriesDialog=dialog;
                dialog.setOnDismissListener(d->{if(seriesDialog==dialog)seriesDialog=null;});
                dialog.show();
            });
        });
    }

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
                    if(currentEntry!=null&&isAdultEntry(currentEntry)){savePlaybackPosition();releasePlayer();currentEntry=null;pendingPlayerStateRestore=false;Toast.makeText(this,"La lecture a été arrêtée pour protéger le profil Enfant.",Toast.LENGTH_LONG).show();}
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
                                if(verifyPin(pinHash,pin.getText().toString())){
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
    // Les pages Web peuvent naviguer librement en HTTP(S), mais pas déclencher des schémas externes ou locaux.
    private boolean isAllowedWebNavigation(String rawUrl){
        if(rawUrl==null)return false;
        try{
            Uri uri=Uri.parse(rawUrl);
            String scheme=uri.getScheme();
            return uri.getHost()!=null&&("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme));
        }catch(Exception ignored){return false;}
    }
    private boolean shouldBlockWebNavigation(WebView view,String rawUrl){return !isAllowedWebNavigation(rawUrl);}
    private boolean isWebSection(String target){return "MTV".equals(target)||"WITV".equals(target)||"FRSTV".equals(target)||"FSLIVE".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target)||"BRAFLIX".equals(target);}
    private String webUrlFor(String target){if("WITV".equals(target))return "https://wiflix.mov/";if("FRSTV".equals(target))return "https://french-stream.net/";if("FSLIVE".equals(target))return "https://fstv.one/";if("EPI XTV".equals(target))return "https://www.xnxx.com/";if("DUDETV".equals(target))return "https://theporndude.com/";if("BRAFLIX".equals(target))return "https://braflix.uk/";return "https://oxax.tv/";}
    private void showMtvWebView(){showSiteWebView("https://oxax.tv/");}
    private void showSiteWebView(String url){
        activeWebUrl=url;
        if(mtvWebView==null){
            mtvWebView=new WebView(this);mtvWebView.setId(View.generateViewId());mtvWebView.setBackgroundColor(Color.BLACK);
            mtvWebView.setFocusable(true);mtvWebView.setFocusableInTouchMode(true);
            mtvWebView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mtvWebView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            android.webkit.WebSettings settings=mtvWebView.getSettings();
            settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);if(Build.VERSION.SDK_INT>=26)settings.setSafeBrowsingEnabled(true);
            settings.setDatabaseEnabled(true);settings.setLoadsImagesAutomatically(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(true);
            settings.setLoadWithOverviewMode(true);settings.setUseWideViewPort(true);
            settings.setMediaPlaybackRequiresUserGesture(false);settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            mtvWebView.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){return request==null||shouldBlockWebNavigation(view,request.getUrl().toString());}
                @SuppressWarnings("deprecation")
                @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return shouldBlockWebNavigation(view,url);}
                @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();}
                @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){super.onPageStarted(view,url,favicon);if(url!=null&&!url.equals("about:blank"))activeWebUrl=url;}
                @Override public void onPageFinished(WebView view,String loaded){super.onPageFinished(view,loaded);View focused=getCurrentFocus();View menu=null;if(webContainer!=null&&webContainer.getChildCount()>0&&webContainer.getChildAt(0) instanceof ViewGroup){ViewGroup nav=(ViewGroup)webContainer.getChildAt(0);if(nav.getChildCount()>0)menu=nav.getChildAt(0);}if(view==mtvWebView&&isWebSection(section)&&view.getVisibility()==View.VISIBLE&&focused!=menu)view.requestFocus();}
                @Override public void onReceivedError(WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceError error){super.onReceivedError(view,request,error);if(request.isForMainFrame())postUi(()->Toast.makeText(MainActivity.this,"Erreur de chargement de la page Web.",Toast.LENGTH_LONG).show());}
                @Override public void onReceivedHttpError(WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceResponse response){super.onReceivedHttpError(view,request,response);if(request.isForMainFrame()&&response.getStatusCode()>=400)postUi(()->Toast.makeText(MainActivity.this,"Erreur Web HTTP "+response.getStatusCode(),Toast.LENGTH_LONG).show());}
                @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){
                    if(view==mtvWebView){
                        String reload=activeWebUrl;
                        if(webCustomView!=null)hideWebCustomView();
                        if(webContainer!=null&&webContainer.getParent()==content)content.removeView(webContainer);
                        for(WebView popup:new HashSet<>(webPopupViews)){try{popup.stopLoading();if(popup.getParent() instanceof ViewGroup)((ViewGroup)popup.getParent()).removeView(popup);popup.destroy();}catch(Exception ignored){}}
                        webPopupViews.clear();
                        if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
                        try{view.stopLoading();view.destroy();}catch(Exception ignored){}
                        mtvWebView=null;webContainer=null;
                        postUi(()->{Toast.makeText(MainActivity.this,"Le lecteur Web a redémarré.",Toast.LENGTH_LONG).show();if(isWebSection(section)&&reload!=null&&!reload.isEmpty())showSiteWebView(reload);});
                    }
                    return true;
                }
            });
            mtvWebView.setWebChromeClient(new WebChromeClient(){
                @Override public void onShowCustomView(View view,CustomViewCallback callback){showWebCustomView(view,callback);}
                @Override public void onHideCustomView(){hideWebCustomView();}
                @Override public void onShowCustomView(View view,int requestedOrientation,CustomViewCallback callback){showWebCustomView(view,callback);}
                @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
                    // Refuse les fenêtres déclenchées automatiquement par un script sans action utilisateur.
                    if(!isUserGesture||resultMsg==null||!(resultMsg.obj instanceof WebView.WebViewTransport))return false;
                    WebView popup=new WebView(MainActivity.this);popup.getSettings().setJavaScriptEnabled(true);popup.getSettings().setDomStorageEnabled(true);popup.getSettings().setAllowFileAccess(false);popup.getSettings().setAllowContentAccess(false);popup.getSettings().setAllowFileAccessFromFileURLs(false);popup.getSettings().setAllowUniversalAccessFromFileURLs(false);popup.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);if(Build.VERSION.SDK_INT>=26)popup.getSettings().setSafeBrowsingEnabled(true);
                     popup.setWebViewClient(new WebViewClient(){
                         @Override public boolean shouldOverrideUrlLoading(WebView child,android.webkit.WebResourceRequest request){return request==null||shouldBlockWebNavigation(child,request.getUrl().toString());}
                         @SuppressWarnings("deprecation")
                         @Override public boolean shouldOverrideUrlLoading(WebView child,String url){return shouldBlockWebNavigation(child,url);}
                         @Override public void onPageStarted(WebView child,String url,android.graphics.Bitmap favicon){super.onPageStarted(child,url,favicon);if(url!=null&&!url.equals("about:blank")&&mtvWebView!=null){activeWebUrl=url;mtvWebView.loadUrl(url);child.postDelayed(()->{webPopupViews.remove(child);try{child.stopLoading();if(child.getParent() instanceof ViewGroup)((ViewGroup)child.getParent()).removeView(child);child.destroy();}catch(Exception ignored){}},500L);}}
                         @Override public boolean onRenderProcessGone(WebView child,android.webkit.RenderProcessGoneDetail detail){webPopupViews.remove(child);try{child.stopLoading();if(child.getParent() instanceof ViewGroup)((ViewGroup)child.getParent()).removeView(child);child.destroy();}catch(Exception ignored){}return true;}
                     });
                    webPopupViews.add(popup);android.webkit.WebView.WebViewTransport transport=(android.webkit.WebView.WebViewTransport)resultMsg.obj;transport.setWebView(popup);resultMsg.sendToTarget();return true;
                }
                @Override public void onCloseWindow(WebView window){if(window!=mtvWebView){webPopupViews.remove(window);window.stopLoading();window.destroy();}}
            });
            mtvWebView.setOnFocusChangeListener((v,focused)->{if(focused)v.setBackgroundColor(Color.BLACK);});
        }
        chipScroll.setVisibility(View.GONE);status.setVisibility(View.GONE);catalogScroll.setVisibility(View.GONE);if(playerArea!=null)playerArea.setVisibility(View.GONE);
        if(webContainer==null){
            webContainer=column();webContainer.setBackgroundColor(Color.BLACK);
            LinearLayout webNav=row();webNav.setPadding(dp(8),dp(4),dp(8),dp(4));
            TextView backMenu=text("☰  MENU PRINCIPAL",13,Color.WHITE,true);backMenu.setId(View.generateViewId());backMenu.setFocusable(true);backMenu.setFocusableInTouchMode(true);backMenu.setClickable(true);backMenu.setPadding(dp(16),dp(8),dp(16),dp(8));backMenu.setBackground(bg(ACCENT,10));
            backMenu.setOnClickListener(v->navigateTo("ACCUEIL"));backMenu.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){mtvWebView.requestFocus();return true;}if(keyCode==KeyEvent.KEYCODE_DPAD_UP){TextView currentNav=navViews.get(section);if(currentNav!=null){currentNav.requestFocus();return true;}}return false;});webNav.addView(backMenu,new LinearLayout.LayoutParams(-2,dp(42)));
            webContainer.addView(webNav,new LinearLayout.LayoutParams(-1,dp(50)));
            webContainer.addView(mtvWebView,new LinearLayout.LayoutParams(-1,0,1));
            mtvWebView.setNextFocusUpId(backMenu.getId());mtvWebView.setNextFocusDownId(View.NO_ID);backMenu.setNextFocusDownId(mtvWebView.getId());
        }
        if(webContainer.getParent()==null)content.addView(webContainer,Math.min(3,content.getChildCount()),new LinearLayout.LayoutParams(-1,0,1));
        webContainer.setVisibility(View.VISIBLE);mtvWebView.setVisibility(View.VISIBLE);mtvWebView.setFocusable(true);mtvWebView.setFocusableInTouchMode(true);
        mtvWebView.onResume();
        mtvWebView.bringToFront();mtvWebView.requestFocus();
        if(pendingWebViewState!=null){Bundle saved=pendingWebViewState;pendingWebViewState=null;if(mtvWebView.restoreState(saved)==null){if(!shouldBlockWebNavigation(mtvWebView,url))mtvWebView.loadUrl(url);}}else if(mtvWebView.getUrl()==null||!mtvWebView.getUrl().equals(url)){if(!shouldBlockWebNavigation(mtvWebView,url))mtvWebView.loadUrl(url);}
    }
    private void showWebCustomView(View view,WebChromeClient.CustomViewCallback callback){
        if(webCustomView!=null){if(callback!=null)callback.onCustomViewHidden();return;}
        webCustomView=view;webCustomViewCallback=callback;
        view.setFocusable(true);view.setFocusableInTouchMode(true);
        webOriginalSystemUiVisibility=getWindow().getDecorView().getSystemUiVisibility();
        webFullscreenContainer=new FrameLayout(this);webFullscreenContainer.setBackgroundColor(Color.BLACK);
        webFullscreenContainer.addView(view,new FrameLayout.LayoutParams(-1,-1));
        View decor=getWindow().getDecorView();
        if(decor instanceof ViewGroup)((ViewGroup)decor).addView(webFullscreenContainer,new ViewGroup.LayoutParams(-1,-1));
        view.requestFocus();
        applyImmersiveMode();
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
    private void pauseWebMedia(){
        if(mtvWebView==null)return;
        try{mtvWebView.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(media){try{media.pause();}catch(e){}});})();",null);}catch(Exception ignored){}
        mtvWebView.onPause();
    }
    private void hideMtvWebView(){
        activeWebUrl="";
        pauseWebMedia();
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
                if(!putSecureString("mtv_playlist_url",value)){Toast.makeText(this,"Impossible de protéger cette adresse sur cet appareil.",Toast.LENGTH_LONG).show();return;}
                mtvPlaylistUrl=value;loadMtvPlaylist(value);
            }).show();
    }
    private void loadMtvPlaylist(String url){
        final int request=++catalogRequestId;
        status.setText("Chargement de la source MTV…");
        executeIo(()->{
            ArrayList<MediaEntry> parsed=new ArrayList<>();String error=null;
            try{
                String data=get(url);if(request!=catalogRequestId||destroyed)return;
                BufferedReader reader=new BufferedReader(new StringReader(data));
                String line,name=null,group="MTV",logo="",tvgId="";Map<String,String> streamHeaders=new HashMap<>();int count=0;
                while((line=reader.readLine())!=null&&count<MAX_CATALOG_ITEMS&&request==catalogRequestId&&!destroyed){
                    if(line.length()>1024*1024)throw new IOException("Ligne M3U trop longue (limite 1 Mo)");
                    line=line.trim();
                    if(line.startsWith("#EXTINF:")){
                        streamHeaders.clear();
                        name=line.substring(line.lastIndexOf(',')+1).trim();group="MTV";logo="";tvgId="";
                        int g=line.indexOf("group-title=\"");
                        if(g>=0){int e=line.indexOf('"',g+13);if(e>g)group=line.substring(g+13,e);}
                        int tid=line.indexOf("tvg-id=\"");
                        if(tid>=0){int e=line.indexOf('"',tid+8);if(e>tid)tvgId=line.substring(tid+8,e);}
                        int l=line.indexOf("tvg-logo=\"");
                        if(l>=0){int e=line.indexOf('"',l+10);if(e>l)logo=line.substring(l+10,e);}
                    }else if(line.startsWith("#EXTVLCOPT:")||line.startsWith("#EXTHTTP:")){parseM3uHttpOption(line,streamHeaders);
                    }else if(!line.isEmpty()&&!line.startsWith("#")&&name!=null){
                        String resolvedUrl=resolveM3uEntryUrl(url,line);
                        if(resolvedUrl!=null){
                            MediaEntry entry=new MediaEntry(name,resolvedUrl,group,logo,"m3u");entry.tvgId=tvgId;entry.httpHeaders.putAll(streamHeaders);parsed.add(entry);count++;
                        }name=null;
                    }
                }
            }catch(Exception ex){error=ex.getMessage();}
            if(request!=catalogRequestId||destroyed)return;
            final String err=error;
            postUi(()->{
                if(request!=catalogRequestId)return;
                if(err!=null){status.setText("Erreur source MTV : "+err);return;}
                renderOffset=0;mtvItems.clear();mtvItems.addAll(parsed);
                status.setText(parsed.size()+" éléments chargés • Source MTV dédiée");
                if(section.equals("MTV"))renderList();
            });
        });
    }
    private void showSettings(){String[] options={"Changer de profil (Adulte / Enfant)","Ajouter une playlist M3U","Gérer les playlists M3U","Connexion Xtream Codes","Configurer le guide TV (XMLTV)","Définir / modifier le code parental","Effacer l’historique et les reprises","Configurer la source MTV"};new AlertDialog.Builder(this).setTitle("GLOBAL TV • Paramètres").setItems(options,(d,w)->{if(w==0)showProfileDialog();else if(w==1)showM3uDialog();else if(w==2)showPlaylistManager();else if(w==3)showXtreamDialog();else if(w==4)showEpgDialog();else if(w==5)showPinDialog();else if(w==6)new AlertDialog.Builder(this).setTitle("Effacer l’historique ?").setMessage("Les positions de lecture et l’historique seront supprimés.").setNegativeButton("Annuler",null).setPositiveButton("Effacer",(a,b)->{Map<String,?> allPrefs=prefs.getAll();SharedPreferences.Editor ed=prefs.edit();for(String k:allPrefs.keySet())if(k.startsWith("history_")||k.startsWith("position_"))ed.remove(k);ed.apply();renderList();}).show();else if(w==7)showMtvPlaylistDialog();}).show();}
    private boolean isAdultWebSection(String target){return "MTV".equals(target)||"EPI XTV".equals(target)||"DUDETV".equals(target);}
    private boolean isProtectedSection(String target){return target.equals("FILMS")||target.equals("SÉRIES")||target.equals("ANIMÉS")||isAdultWebSection(target);}
    private void requestParentalAccess(String target){
 String expected=prefs.getString("parental_pin_hash","");EditText pin=field("Code PIN parental");pin.setInputType(2);
 new AlertDialog.Builder(this).setTitle("Contrôle parental").setMessage("Entre le code PIN pour accéder à cette rubrique.").setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Valider",(d,w)->{
  if(verifyPin(expected,pin.getText().toString())){parentalAuthorizedTarget=target;navigateTo(target);}else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
 }).show();
}
    private void showPinDialog(){
 String existing=prefs.getString("parental_pin_hash","");
 if(existing.isEmpty()){showPinEditor();return;}
 EditText current=field("Code PIN actuel");current.setInputType(2);
 new AlertDialog.Builder(this).setTitle("Vérification du code parental").setMessage("Entre le code actuel avant de le modifier ou de le désactiver.").setView(current).setNegativeButton("Annuler",null).setPositiveButton("Continuer",(d,w)->{
  if(verifyPin(existing,current.getText().toString()))showPinEditor();else Toast.makeText(this,"Code PIN incorrect",Toast.LENGTH_LONG).show();
 }).show();
}
private void showPinEditor(){
 EditText pin=field("Nouveau code PIN (4 à 8 chiffres)");pin.setInputType(2);
 new AlertDialog.Builder(this).setTitle("Contrôle parental").setMessage("Laisse vide pour désactiver le contrôle parental.").setView(pin).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{
  String v=pin.getText().toString();if(v.isEmpty())prefs.edit().remove("parental_pin_hash").apply();else if(v.matches("\\d{4,8}")){String secureHash=createPinHash(v);if(secureHash!=null)prefs.edit().putString("parental_pin_hash",secureHash).apply();else Toast.makeText(this,"Impossible de sécuriser le code PIN sur cet appareil.",Toast.LENGTH_LONG).show();}else Toast.makeText(this,"Utilise 4 à 8 chiffres",Toast.LENGTH_LONG).show();
 }).show();
}
    private byte[] derivePinKey(String pin,byte[] salt,int iterations){PBEKeySpec spec=null;try{spec=new PBEKeySpec(pin.toCharArray(),salt,iterations,256);return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();}catch(Exception e){return null;}finally{if(spec!=null)spec.clearPassword();}}
    private String createPinHash(String pin){try{byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);int iterations=120000;byte[] derived=derivePinKey(pin,salt,iterations);if(derived==null)return null;return "pbkdf2_sha1$"+iterations+"$"+android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP)+"$"+android.util.Base64.encodeToString(derived,android.util.Base64.NO_WRAP);}catch(Exception e){return null;}}
    private boolean verifyPin(String stored,String pin){
 if(stored==null||stored.isEmpty())return false;
 long now=System.currentTimeMillis();
 long lockoutUntil=prefs.getLong("parental_pin_lockout_until",0L);
 if(lockoutUntil>now){
  long remaining=(lockoutUntil-now+59999L)/60000L;
  Toast.makeText(this,"Trop de tentatives. Réessaie dans "+remaining+" min.",Toast.LENGTH_LONG).show();
  return false;
 }
 if(lockoutUntil>0L)prefs.edit().remove("parental_pin_lockout_until").putInt("parental_pin_failed_attempts",0).apply();
 boolean valid=false;
 if(stored.startsWith("pbkdf2_sha1$")){
  try{
   String[] parts=stored.split("\\$");
   if(parts.length==4){
    int iterations=Integer.parseInt(parts[1]);
    if(iterations>=100000&&iterations<=500000){
     byte[] salt=android.util.Base64.decode(parts[2],android.util.Base64.NO_WRAP);
     byte[] expected=android.util.Base64.decode(parts[3],android.util.Base64.NO_WRAP);
     byte[] actual=derivePinKey(pin,salt,iterations);
     valid=actual!=null&&MessageDigest.isEqual(expected,actual);
    }
   }
  }catch(Exception ignored){}
 }else if(stored.length()==64&&stored.equals(hash(pin))){
  valid=true;
  String upgraded=createPinHash(pin);
  if(upgraded!=null)prefs.edit().putString("parental_pin_hash",upgraded).apply();
 }
 if(valid){
  prefs.edit().remove("parental_pin_lockout_until").putInt("parental_pin_failed_attempts",0).apply();
  return true;
 }
 int failures=prefs.getInt("parental_pin_failed_attempts",0)+1;
 SharedPreferences.Editor editor=prefs.edit().putInt("parental_pin_failed_attempts",failures);
 if(failures>=5){
  editor.putLong("parental_pin_lockout_until",now+5L*60L*1000L).putInt("parental_pin_failed_attempts",0);
  editor.apply();
  Toast.makeText(this,"5 tentatives incorrectes. Accès bloqué pendant 5 minutes.",Toast.LENGTH_LONG).show();
 }else{
  editor.apply();
  Toast.makeText(this,"Code PIN incorrect ("+failures+"/5).",Toast.LENGTH_SHORT).show();
 }
 return false;
}
    private String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.US,"%02x",x));return out.toString();}catch(Exception e){return value;}}
    private String key(String value){return hash(value).substring(0,24);}
    private void clearPlaybackPosition(MediaEntry entry){if(entry==null||entry.url==null||entry.url.isEmpty()||prefs==null)return;try{prefs.edit().remove("position_"+key(entry.url)).apply();}catch(Exception ignored){}}
    private void savePlaybackPosition(){
        ExoPlayer activePlayer=player;
        MediaEntry activeEntry=currentEntry;
        if(activePlayer==null||activeEntry==null||activeEntry.url==null||activeEntry.url.isEmpty()||prefs==null)return;
        try{
            if(player!=activePlayer||destroyed)return;
            long position=activePlayer.getCurrentPosition();
            if(position>0&&player==activePlayer&&!destroyed)
                prefs.edit().putLong("position_"+key(activeEntry.url),position).apply();
        }catch(RuntimeException playerStateError){
            android.util.Log.w("GLOBALTV","Position de lecture non sauvegardée : lecteur indisponible",playerStateError);
        }
    }
    private void stopPlaybackPositionSaver(){if(playbackPositionSaver!=null)mainHandler.removeCallbacks(playbackPositionSaver);playbackPositionSaver=null;}
    private void startPlaybackPositionSaver(ExoPlayer activePlayer){
        stopPlaybackPositionSaver();
        if(activePlayer==null||destroyed)return;
        playbackPositionSaver=new Runnable(){
            @Override public void run(){
                if(destroyed||player!=activePlayer||currentEntry==null){playbackPositionSaver=null;return;}
                savePlaybackPosition();
                mainHandler.postDelayed(this,10000L);
            }
        };
        mainHandler.postDelayed(playbackPositionSaver,10000L);
    }
    private void showCreateFavoriteGroup(){EditText name=field("Nom de la liste");new AlertDialog.Builder(this).setTitle("Créer une liste de favoris").setView(name).setNegativeButton("Annuler",null).setPositiveButton("Créer",(d,w)->{String n=name.getText().toString().trim();if(n.isEmpty())return;if(favoriteGroups.containsKey(n)){Toast.makeText(this,"Cette liste existe déjà",Toast.LENGTH_SHORT).show();return;}favoriteGroups.put(n,new LinkedHashSet<>());prefs.edit().putStringSet("favorite_groups",new HashSet<>(favoriteGroups.keySet())).apply();activeFilter=n;updateChips();renderList();}).show();}
    private void showAssignFavoriteGroup(MediaEntry entry){if(favoriteGroups.isEmpty()){Toast.makeText(this,"Crée d’abord une liste de favoris.",Toast.LENGTH_SHORT).show();return;}String[] names=favoriteGroups.keySet().toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Ajouter à une liste").setItems(names,(d,i)->{Set<String> urls=favoriteGroups.get(names[i]);urls.add(entry.url);putSecureStringSet("favorite_group_"+key(names[i]),new HashSet<>(urls));Toast.makeText(this,"Ajouté à "+names[i],Toast.LENGTH_SHORT).show();}).setNegativeButton("Annuler",null).show();}
    private void showEpgDialog(){EditText input=field("URL du fichier XMLTV (HTTP ou HTTPS)");input.setText(epgUrl);new AlertDialog.Builder(this).setTitle("Guide TV XMLTV").setMessage("Ajoute l’URL XMLTV de ta source. Les chaînes doivent avoir un tvg-id correspondant.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){if(!putSecureString("epg_url",u)){Toast.makeText(this,"Impossible d’enregistrer cette adresse sur cet appareil.",Toast.LENGTH_LONG).show();return;}epgUrl=u;loadEpg(u);}else Toast.makeText(this,"Adresse HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    // Limite la taille des flux XML externes pour eviter une consommation memoire excessive.
    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;
        LimitedInputStream(InputStream input,long maxBytes){super(input);this.maxBytes=maxBytes;}
        @Override public int read()throws IOException{
            int value=super.read();
            if(value!=-1&&++bytesRead>maxBytes)throw new IOException("Guide TV trop volumineux (limite 32 Mo)");
            return value;
        }
        @Override public int read(byte[] buffer,int offset,int length)throws IOException{
            int count=super.read(buffer,offset,length);
            if(count>0&&(bytesRead+=count)>maxBytes)throw new IOException("Guide TV trop volumineux (limite 32 Mo)");
            return count;
        }
    }
    private InputStream limitEpgStream(InputStream source,long maxBytes){
        return new FilterInputStream(source){
            private long total=0L;
            @Override public int read()throws IOException{
                int value=super.read();
                if(value!=-1&&++total>maxBytes)throw new IOException("Guide TV trop volumineux (limite 64 Mo)");
                return value;
            }
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{
                int count=super.read(buffer,offset,length);
                if(count>0){total+=count;if(total>maxBytes)throw new IOException("Guide TV trop volumineux (limite 64 Mo)");}
                return count;
            }
        };
    }
    private void loadEpg(String url){
        final int request=++epgRequestId;
        status.setText("Chargement du guide TV…");
        executeIo(()->{
            Map<String,String> now=new HashMap<>(),next=new HashMap<>();
            String err=null;
            try{
                HttpURLConnection connection=open(url);
                try{
                    int code=connection.getResponseCode();
                    if(code<200||code>=300)throw new IOException("HTTP "+code);
                    InputStream raw=connection.getInputStream();
                    if(raw==null)throw new IOException("Réponse EPG vide");
                    try(InputStream input=limitEpgStream(raw,64L*1024L*1024L)){
                        XmlPullParser parser=XmlPullParserFactory.newInstance().newPullParser();
                        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
                        parser.setInput(input,"UTF-8");
                        String channel=null;
                        long start=0L,stop=0L,nowMs=System.currentTimeMillis();
                        int event=parser.getEventType();
                        while(event!=XmlPullParser.END_DOCUMENT&&request==epgRequestId&&!destroyed){
                            if(event==XmlPullParser.START_TAG&&"programme".equals(parser.getName())){
                                channel=parser.getAttributeValue(null,"channel");
                                if(channel!=null&&channel.length()>256)channel=channel.substring(0,256);
                                start=parseXmlTvTime(parser.getAttributeValue(null,"start"));
                                stop=parseXmlTvTime(parser.getAttributeValue(null,"stop"));
                            }else if(event==XmlPullParser.START_TAG&&"title".equals(parser.getName())&&channel!=null){
                                String title=parser.nextText();
                                if(title!=null&&!title.isEmpty()){
                                    if(title.length()>512)title=title.substring(0,512);
                                    if(start<=nowMs&&stop>nowMs&&(!now.containsKey(channel)||now.size()<5000))now.put(channel,title);
                                    else if(start>nowMs&&!next.containsKey(channel)&&next.size()<5000)next.put(channel,title);
                                }
                            }
                            event=parser.next();
                        }
                    }
                }finally{connection.disconnect();}
            }catch(Exception e){err=e.getMessage();}
            if(request!=epgRequestId||destroyed)return;
            final String error=err;
            epgNow.clear();epgNow.putAll(now);
            epgNext.clear();epgNext.putAll(next);
            postUi(()->{
                if(request!=epgRequestId)return;
                status.setText(error==null?now.size()+" programmes en cours • "+next.size()+" à suivre • Guide TV XMLTV":"Erreur EPG : "+error);
                renderList();
            });
        });
    }
    private long parseXmlTvTime(String value){if(value==null)return 0;try{String v=value.trim();SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);if(v.length()==14)v+=" +0000";else if(v.length()>14&&v.charAt(14)!=' ')v=v.substring(0,14)+" "+v.substring(14);Date d=f.parse(v);return d==null?0:d.getTime();}catch(Exception e){return 0;}}
    private void showM3uDialog(){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("https://exemple.com/playlist.m3u");input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);input.setText("");new AlertDialog.Builder(this).setTitle("Ajouter une playlist M3U").setMessage("Ajoute une nouvelle source. Les chaînes des playlists seront réunies dans le même catalogue.").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Ajouter et charger",(d,w)->{String u=input.getText().toString().trim();if(u.startsWith("https://")||u.startsWith("http://")){{section="TV EN DIRECT";refreshAllNavStyles();loadPlaylist(u);}}else Toast.makeText(this,"Adresse HTTP/HTTPS requise",Toast.LENGTH_LONG).show();}).show();}
    private void showPlaylistManager(){ArrayList<String> urls=new ArrayList<>(playlistSources);if(urls.isEmpty()){new AlertDialog.Builder(this).setTitle("Sources M3U").setMessage("Aucune source enregistrée.").setPositiveButton("Ajouter", (d,w)->showM3uDialog()).show();return;}String[] labels=new String[urls.size()];for(int i=0;i<urls.size();i++)labels[i]=(i+1)+". "+Uri.parse(urls.get(i)).getHost()+" — "+urls.get(i);new AlertDialog.Builder(this).setTitle("Sources M3U • "+urls.size()).setItems(labels,(d,which)->new AlertDialog.Builder(this).setTitle("Gérer cette source").setMessage(urls.get(which)).setNegativeButton("Fermer",null).setNeutralButton("Supprimer",(a,b)->{playlistSources.remove(urls.get(which));putSecureStringSet("m3u_sources",new HashSet<>(playlistSources));loadAllPlaylists();}).setPositiveButton("Recharger",(a,b)->loadPlaylist(urls.get(which))).show()).setPositiveButton("Ajouter une source",(d,w)->showM3uDialog()).setNegativeButton("Fermer",null).show();}
    private String normalizeXtreamBase(String raw)throws IOException{
        String value=raw==null?"":raw.trim();
        try{
            java.net.URI uri=new java.net.URI(value);
            String scheme=uri.getScheme();
            if(uri.getHost()==null||scheme==null||
                (!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))||
                uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
                uri.getPort()==0||uri.getPort()>65535)
                throw new IOException("URL du serveur Xtream invalide");
            String path=uri.getRawPath()==null?"":uri.getRawPath();
            if(path.contains("/../")||path.endsWith("/..")||path.contains("/./")||path.endsWith("/."))
                throw new IOException("Chemin du serveur Xtream invalide");
            while(value.endsWith("/"))value=value.substring(0,value.length()-1);
            if(path.toLowerCase(Locale.ROOT).endsWith("/player_api.php"))
                value=value.substring(0,value.length()-"/player_api.php".length());
            return value;
        }catch(java.net.URISyntaxException e){
            throw new IOException("URL du serveur Xtream invalide",e);
        }
    }
    private void showXtreamDialog(){LinearLayout form=column();form.setPadding(dp(8),dp(4),dp(8),dp(4));EditText host=field("URL du serveur (https://serveur:port)");EditText user=field("Nom d’utilisateur");EditText pass=field("Mot de passe");pass.setInputType(129);form.addView(host);form.addView(user);form.addView(pass);new AlertDialog.Builder(this).setTitle("Connexion Xtream Codes").setMessage("Utilise les identifiants fournis par ton fournisseur IPTV autorisé. HTTPS est recommandé.").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Se connecter",(d,w)->{String h=host.getText().toString().trim(),u=user.getText().toString().trim(),p=pass.getText().toString();try{String base=normalizeXtreamBase(h);if(u.isEmpty()||p.isEmpty())throw new IOException("Identifiants manquants");{xtreamBase=base;xtreamUser=u;xtreamPass=p;section="ACCUEIL";heading.setText("Bienvenue sur GLOBAL TV");loadXtream();}}catch(Exception e){Toast.makeText(this,e.getMessage()==null?"Vérifie l’URL et les identifiants":e.getMessage(),Toast.LENGTH_LONG).show();}}).show();}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(10),dp(6),dp(10),dp(6));return e;}
    private void installBackNavigation(){
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){if(!handleBackAction())finish();}
        });
    }
    private boolean handleBackAction(){
        if(webCustomView!=null){hideWebCustomView();return true;}
        if(isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE){
            if(mtvWebView.canGoBack()){mtvWebView.goBack();return true;}
            navigateTo("ACCUEIL");return true;
        }
        if(playerView!=null){
            if(playerFullscreen){togglePlayerExpanded();return true;}
            savePlaybackPosition();releasePlayer();status.setText("Lecture fermée");return true;
        }
        return false;
    }
    private boolean handlePlayerRemoteKey(int keyCode){
        if(player==null)return false;
        switch(keyCode){
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if(player.isPlaying())player.pause();else player.play();return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                player.pause();return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if(player.hasNextMediaItem())player.seekToNextMediaItem();return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if(player.hasPreviousMediaItem())player.seekToPreviousMediaItem();return true;
            default:return false;
        }
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK&&handleBackAction())return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&handlePlayerRemoteKey(event.getKeyCode()))return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&webCustomView==null&&isWebSection(section)&&webContainer!=null&&webContainer.getVisibility()==View.VISIBLE&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&mtvWebView!=null&&mtvWebView.hasFocus()&&!mtvWebView.canScrollVertically(-1)){
            View menu=null;View webNav=webContainer.getChildAt(0);if(webNav instanceof ViewGroup)menu=((ViewGroup)webNav).getChildAt(0);if(menu!=null){menu.requestFocus();return true;}
        }
        if(webCustomView==null&&isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN&&!mtvWebView.hasFocus()){View menu=null;if(webContainer!=null&&webContainer.getChildCount()>0&&webContainer.getChildAt(0) instanceof ViewGroup){ViewGroup nav=(ViewGroup)webContainer.getChildAt(0);if(nav.getChildCount()>0)menu=nav.getChildAt(0);}if(menu!=null&&menu.hasFocus()){mtvWebView.requestFocus();return true;}}
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
    @Override protected void onSaveInstanceState(Bundle outState){
        super.onSaveInstanceState(outState);
        outState.putString("session_section",section);outState.putString("session_filter",activeFilter);outState.putString("session_search",search==null?"":search.getText().toString());
        if(catalogScroll!=null)outState.putInt("session_catalog_scroll",catalogScroll.getScrollY());
        if(player!=null&&currentEntry!=null){long position=player.getCurrentPosition();try{outState.putString("session_player_url",encryptLocalValue(currentEntry.url,"session_player_url"));}catch(Exception secureError){android.util.Log.e("GLOBALTV","Impossible de protéger l’URL de reprise",secureError);}outState.putString("session_player_name",currentEntry.name==null?"Reprise":currentEntry.name);outState.putString("session_player_group",currentEntry.group==null?"Reprise":currentEntry.group);outState.putLong("session_player_position",position);outState.putBoolean("session_player_playing",player.getPlayWhenReady()||resumePlaybackOnStart);outState.putBoolean("session_player_fullscreen",playerFullscreen);if(position>0L)prefs.edit().putLong("position_"+key(currentEntry.url),position).apply();}
        if(isWebSection(section)&&mtvWebView!=null&&mtvWebView.getVisibility()==View.VISIBLE){try{outState.putString("session_web_url",encryptLocalValue(activeWebUrl,"session_web_url"));}catch(Exception secureError){android.util.Log.e("GLOBALTV","Impossible de protéger l’URL Web de session",secureError);}}
    }
    @Override protected void onResume(){super.onResume();if(mtvWebView!=null&&isWebSection(section)&&mtvWebView.getVisibility()==View.VISIBLE)mtvWebView.onResume();}
    @Override protected void onPause(){pauseWebMedia();super.onPause();}
    @Override protected void onStart(){super.onStart();registerNetworkMonitor();if(resumePlaybackOnStart&&player!=null){resumePlaybackOnStart=false;player.play();}if(player!=null){startPlaybackPositionSaver(player);if(player.getPlaybackState()==Player.STATE_BUFFERING)startBufferingWatchdog(player,currentEntry);}}
    @Override protected void onStop(){unregisterNetworkMonitor();stopBufferingWatchdog(null);super.onStop();if(player!=null){resumePlaybackOnStart=player.getPlayWhenReady();savePlaybackPosition();stopPlaybackPositionSaver();player.pause();}}
    @Override protected void onDestroy(){
        destroyed=true;
        catalogRequestId++;
        epgRequestId++;
        seriesRequestId++;
        unregisterNetworkMonitor();
        if(pendingSearch!=null){mainHandler.removeCallbacks(pendingSearch);pendingSearch=null;}
        if(networkRecoveryRunnable!=null){mainHandler.removeCallbacks(networkRecoveryRunnable);networkRecoveryRunnable=null;}
        stopBufferingWatchdog(null);
        if(splashTimeoutRunnable!=null){mainHandler.removeCallbacks(splashTimeoutRunnable);splashTimeoutRunnable=null;}
        if(brandSplash!=null){BrandSplash splash=brandSplash;brandSplash=null;splash.setProgressComplete();if(splash.getParent() instanceof ViewGroup)((ViewGroup)splash.getParent()).removeView(splash);}
        if(webCustomView!=null)hideWebCustomView();
        for(WebView popup:new HashSet<>(webPopupViews)){try{popup.stopLoading();if(popup.getParent() instanceof ViewGroup)((ViewGroup)popup.getParent()).removeView(popup);popup.destroy();}catch(Exception ignored){}}webPopupViews.clear();
        if(mtvWebView!=null){try{mtvWebView.stopLoading();mtvWebView.loadUrl("about:blank");mtvWebView.clearHistory();if(mtvWebView.getParent() instanceof ViewGroup)((ViewGroup)mtvWebView.getParent()).removeView(mtvWebView);mtvWebView.destroy();}catch(Exception ignored){}mtvWebView=null;}
        savePlaybackPosition();releasePlayer();io.shutdownNow();mainHandler.removeCallbacksAndMessages(null);super.onDestroy();
    }
}

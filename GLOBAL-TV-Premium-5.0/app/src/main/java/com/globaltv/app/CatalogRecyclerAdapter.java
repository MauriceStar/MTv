package com.globaltv.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

/** Two-column, logo-forward catalogue cards optimized for TV remote navigation. */
final class CatalogRecyclerAdapter extends RecyclerView.Adapter<CatalogRecyclerAdapter.Holder> {
    interface EntryAction { void onEntry(MainActivity.MediaEntry entry); }
    interface FavoriteAction { boolean onToggle(MainActivity.MediaEntry entry); }
    interface FavoriteLongAction { void onLongPress(MainActivity.MediaEntry entry); }
    interface DetailProvider { String detail(MainActivity.MediaEntry entry); }
    interface FavoriteStateProvider { boolean isFavorite(MainActivity.MediaEntry entry); }

    private final Context context;
    private final EntryAction entryAction;
    private final FavoriteAction favoriteAction;
    private final FavoriteLongAction favoriteLongAction;
    private final DetailProvider detailProvider;
    private final int accent;
    private final ArrayList<MainActivity.MediaEntry> items = new ArrayList<>();
    private FavoriteStateProvider favoriteStateProvider;

    CatalogRecyclerAdapter(Context context, int accent, EntryAction entryAction, FavoriteAction favoriteAction,
                           FavoriteLongAction favoriteLongAction, DetailProvider detailProvider) {
        this.context=context;
        this.accent=accent;
        this.entryAction=entryAction;
        this.favoriteAction=favoriteAction;
        this.favoriteLongAction=favoriteLongAction;
        this.detailProvider=detailProvider;
        setHasStableIds(true);
    }

    void submit(List<MainActivity.MediaEntry> values) {
        items.clear();
        if(values!=null) items.addAll(values);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) {
        MainActivity.MediaEntry entry=items.get(position);
        return entry==null||entry.url==null?position:((long)entry.url.hashCode()<<32) ^ entry.url.length();
    }

    @Override public Holder onCreateViewHolder(ViewGroup parent,int viewType) {
        LinearLayout root=new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(9),dp(9),dp(9),dp(7));
        root.setFocusable(true);
        root.setClickable(true);
        root.setBackground(background(0xFF17212B,13));
        root.setId(View.generateViewId());
        RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(244));
        params.setMargins(dp(5),dp(5),dp(5),dp(5));
        root.setLayoutParams(params);

        FrameLayout artworkFrame=new FrameLayout(context);
        artworkFrame.setBackground(background(0xFF0D141D,9));
        root.addView(artworkFrame,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(116)));
        ImageView artwork=new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artwork.setPadding(dp(5),dp(5),dp(5),dp(5));
        artworkFrame.addView(artwork,new FrameLayout.LayoutParams(-1,-1));

        TextView title=new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,dp(39));
        titleParams.topMargin=dp(5);
        root.addView(title,titleParams);

        TextView detail=new TextView(context);
        detail.setTextColor(0xFFAAB7C6);
        detail.setTextSize(10);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(detail,new LinearLayout.LayoutParams(-1,dp(27)));

        TextView favorite=new TextView(context);
        favorite.setTextColor(accent);
        favorite.setTextSize(19);
        favorite.setGravity(Gravity.CENTER);
        favorite.setFocusable(true);
        favorite.setClickable(true);
        favorite.setBackground(background(0xFF202B37,8));
        favorite.setContentDescription("Ajouter aux favoris");
        LinearLayout.LayoutParams favoriteParams=new LinearLayout.LayoutParams(dp(42),dp(30));
        favoriteParams.gravity=Gravity.END;
        favoriteParams.topMargin=dp(2);
        root.addView(favorite,favoriteParams);
        root.setNextFocusDownId(favorite.getId());
        favorite.setNextFocusUpId(root.getId());
        return new Holder(root,artwork,title,detail,favorite);
    }

    @Override public void onBindViewHolder(Holder holder,int position) {
        MainActivity.MediaEntry entry=items.get(position);
        holder.item=entry;
        holder.root.setTag(entry==null?null:entry.url);
        holder.title.setText(entry==null||entry.name==null?"Contenu":entry.name);
        holder.detail.setText(entry==null||detailProvider==null?"":detailProvider.detail(entry));
        boolean saved=entry!=null&&favoriteStateProvider!=null&&favoriteStateProvider.isFavorite(entry);
        holder.favorite.setText(saved?"♥":"♡");
        holder.favorite.setContentDescription(saved?"Retirer des favoris":"Ajouter aux favoris");
        holder.root.setOnFocusChangeListener((v,focused)->{
            GradientDrawable bg=background(focused?0xFF202B37:0xFF17212B,13);
            if(focused)bg.setStroke(dp(2),accent);
            v.setBackground(bg);
            v.animate().scaleX(focused?1.018f:1f).scaleY(focused?1.018f:1f).setDuration(90).start();
        });
        holder.favorite.setOnFocusChangeListener((v,focused)->{
            GradientDrawable bg=background(focused?accent:0xFF202B37,8);
            if(focused)bg.setStroke(dp(2),Color.WHITE);
            v.setBackground(bg);
            ((TextView)v).setTextColor(focused?Color.BLACK:accent);
        });
        holder.root.setOnClickListener(v->{if(entryAction!=null&&entry!=null)entryAction.onEntry(entry);});
        holder.favorite.setOnClickListener(v->{
            if(favoriteAction!=null&&entry!=null){
                boolean added=favoriteAction.onToggle(entry);
                holder.favorite.setText(added?"♥":"♡");
                holder.favorite.setContentDescription(added?"Retirer des favoris":"Ajouter aux favoris");
            }
        });
        holder.favorite.setOnLongClickListener(v->{if(favoriteLongAction!=null&&entry!=null)favoriteLongAction.onLongPress(entry);return true;});
        holder.artwork.setImageDrawable(background(0xFF0D141D,9));
        if(entry!=null&&entry.logo!=null&&!entry.logo.isEmpty()){
            Glide.with(context).load(entry.logo).override(dp(420),dp(116)).fitCenter().dontAnimate()
                .placeholder(background(0xFF0D141D,9)).error(background(0xFF0D141D,9)).into(holder.artwork);
        } else {
            Glide.with(context).clear(holder.artwork);
        }
    }

    void setFavoriteStateProvider(FavoriteStateProvider provider){favoriteStateProvider=provider;}

    @Override public void onViewRecycled(Holder holder) {
        Glide.with(context).clear(holder.artwork);
        holder.item=null;
        super.onViewRecycled(holder);
    }
    @Override public int getItemCount(){return items.size();}

    private int dp(float value){return (int)(value*context.getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final ImageView artwork;
        final TextView title,detail,favorite;
        MainActivity.MediaEntry item;
        Holder(LinearLayout root,ImageView artwork,TextView title,TextView detail,TextView favorite){
            super(root);this.root=root;this.artwork=artwork;this.title=title;this.detail=detail;this.favorite=favorite;
        }
    }
}

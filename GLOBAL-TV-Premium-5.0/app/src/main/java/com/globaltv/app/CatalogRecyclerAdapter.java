package com.globaltv.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

/** Recycled catalogue rows for Android TV and low-memory Android TV boxes. */
final class CatalogRecyclerAdapter extends RecyclerView.Adapter<CatalogRecyclerAdapter.Holder> {
    interface EntryAction { void onEntry(MainActivity.MediaEntry entry); }
    interface FavoriteAction { boolean onToggle(MainActivity.MediaEntry entry); }
    interface FavoriteLongAction { void onLongPress(MainActivity.MediaEntry entry); }
    interface DetailProvider { String detail(MainActivity.MediaEntry entry); }

    private final Context context;
    private final EntryAction entryAction;
    private final FavoriteAction favoriteAction;
    private final FavoriteLongAction favoriteLongAction;
    private final DetailProvider detailProvider;
    private final ArrayList<MainActivity.MediaEntry> items = new ArrayList<>();

    CatalogRecyclerAdapter(Context context, EntryAction entryAction, FavoriteAction favoriteAction,
                           FavoriteLongAction favoriteLongAction, DetailProvider detailProvider) {
        this.context=context;
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
        int d=context.getResources().getDisplayMetrics().densityDpi;
        LinearLayout root=new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(13),dp(4),dp(12),dp(4));
        root.setFocusable(true);
        root.setClickable(true);
        root.setBackground(background(0xFF17233A,12));
        root.setId(View.generateViewId());

        ImageView artwork=new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(artwork,new LinearLayout.LayoutParams(dp(76),dp(52)));

        LinearLayout meta=new LinearLayout(context);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(dp(12),0,0,0);
        root.addView(meta,new LinearLayout.LayoutParams(0,-1,1f));

        TextView title=new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        meta.addView(title,new LinearLayout.LayoutParams(-1,-2));

        TextView detail=new TextView(context);
        detail.setTextColor(0xFF91A0BA);
        detail.setTextSize(10);
        detail.setMaxLines(2);
        meta.addView(detail,new LinearLayout.LayoutParams(-1,-2));

        TextView favorite=new TextView(context);
        favorite.setTextColor(0xFFFF7199);
        favorite.setTextSize(22);
        favorite.setGravity(Gravity.CENTER);
        favorite.setFocusable(true);
        favorite.setClickable(true);
        favorite.setBackground(background(0xFF17233A,8));
        favorite.setContentDescription("Ajouter aux favoris");
        favorite.setId(View.generateViewId());
        root.setNextFocusRightId(favorite.getId());
        root.addView(favorite,new LinearLayout.LayoutParams(dp(52),-1));
        favorite.setNextFocusLeftId(root.getId());

        return new Holder(root,artwork,title,detail,favorite);
    }

    @Override public void onBindViewHolder(Holder holder,int position) {
        MainActivity.MediaEntry entry=items.get(position);
        holder.item=entry;holder.root.setTag(entry==null?null:entry.url);
        holder.title.setText(entry==null||entry.name==null?"Contenu":entry.name);
        holder.detail.setText(entry==null?"":detailProvider.detail(entry));
        holder.favorite.setText(entry!=null&&favoriteState(entry)?"♥":"♡");
        holder.favorite.setContentDescription(entry!=null&&favoriteState(entry)?"Retirer des favoris":"Ajouter aux favoris");
        holder.root.setOnFocusChangeListener((v,focused)->v.setBackground(background(focused?0xFF746BFF:0xFF17233A,12)));
        holder.favorite.setOnFocusChangeListener((v,focused)->v.setBackground(background(focused?0xFF746BFF:0xFF17233A,8)));
        holder.root.setOnClickListener(v->{if(entryAction!=null&&entry!=null)entryAction.onEntry(entry);});
        holder.favorite.setOnClickListener(v->{if(favoriteAction!=null&&entry!=null){boolean added=favoriteAction.onToggle(entry);holder.favorite.setText(added?"♥":"♡");holder.favorite.setContentDescription(added?"Retirer des favoris":"Ajouter aux favoris");}});
        holder.favorite.setOnLongClickListener(v->{if(favoriteLongAction!=null&&entry!=null)favoriteLongAction.onLongPress(entry);return true;});
        holder.artwork.setImageDrawable(background(0xFF17233A,8));
        if(entry!=null&&entry.logo!=null&&!entry.logo.isEmpty()){
            Glide.with(context).load(entry.logo).override(dp(76),dp(52)).centerCrop().dontAnimate()
                .placeholder(background(0xFF17233A,8)).error(background(0xFF17233A,8)).into(holder.artwork);
        } else {
            Glide.with(context).clear(holder.artwork);
        }
    }

    private boolean favoriteState(MainActivity.MediaEntry entry) {
        return entry!=null&&favoriteStateProvider!=null&&favoriteStateProvider.isFavorite(entry);
    }
    interface FavoriteStateProvider { boolean isFavorite(MainActivity.MediaEntry entry); }
    private FavoriteStateProvider favoriteStateProvider;
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

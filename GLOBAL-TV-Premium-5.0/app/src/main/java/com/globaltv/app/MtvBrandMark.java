package com.globaltv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.DashPathEffect;
import android.graphics.Typeface;
import android.view.View;

/** Logo MTv : parabole complète, mot-symbole attaché et satellite relié. */
public final class MtvBrandMark extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public MtvBrandMark(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Logo MTv avec parabole complète et satellite relié");
    }

    private void paint(int color, float width, Paint.Style style) {
        p.reset(); p.setAntiAlias(true); p.setColor(color); p.setStrokeWidth(width);
        p.setStyle(style); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
    }

    private void gradient(int top, int bottom, float width, Paint.Style style) {
        paint(Color.WHITE, width, style);
        p.setShader(new LinearGradient(0, 150, 0, 680, top, bottom, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 1774f, getHeight() / 887f);

        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        measure.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        measure.setTextSize(370f);
        final float textStart = 545f;
        final float vStart = textStart + measure.measureText("MT") - 18f;
        final float vCenter = vStart + measure.measureText("v") * .50f;
        final float satShift = vCenter - 1380f;

        // Liaison dorée unique, relevée : elle part du foyer de la parabole et rejoint le satellite.
        paint(0xFFFFB52E, 19f, Paint.Style.STROKE);
        p.setShadowLayer(14f, 0f, 0f, 0xFFFFA900);
        path.reset();
        path.moveTo(470f, 300f);
        path.cubicTo(680f, 65f, vCenter - 220f, 35f, vCenter, 135f);
        canvas.drawPath(path, p);
        p.clearShadowLayer();

        // Satellite détaillé, avec panneaux bleus quadrillés et corps métallique.
        canvas.save(); canvas.translate(satShift, 0f); canvas.rotate(16f, 1380f, 130f);
        paint(0xFF173D70, 0f, Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(1185, 62, 1322, 194), 9, 9, p);
        canvas.drawRoundRect(new RectF(1450, 62, 1587, 194), 9, 9, p);
        paint(0xFF5BA5E8, 3f, Paint.Style.STROKE);
        for(int i=1;i<4;i++){canvas.drawLine(1185+i*34,62,1185+i*34,194,p);canvas.drawLine(1450+i*34,62,1450+i*34,194,p);}
        for(int i=1;i<4;i++){canvas.drawLine(1185,62+i*33,1322,62+i*33,p);canvas.drawLine(1450,62+i*33,1587,62+i*33,p);}
        paint(0xFFB8C8D8, 8f, Paint.Style.STROKE);
        canvas.drawLine(1322,128,1345,128,p); canvas.drawLine(1415,128,1450,128,p);
        p.setShader(new LinearGradient(1340,75,1420,190,0xFFF4F7FA,0xFF71869D,Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(1340,76,1420,181),25,25,p); p.setShader(null);
        paint(0xFFEDF3F8, 0f, Paint.Style.FILL); canvas.drawOval(new RectF(1350,54,1410,92),p);
        paint(0xFFFFC52B, 9f, Paint.Style.STROKE); canvas.drawLine(1380,181,1380,207,p);
        canvas.restore();

        // Parabole complète, réflecteur creux métallique et structure arrière.
        p.setShader(new LinearGradient(210,240,520,570,0xFFF5F8FC,0xFF65788D,Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        path.reset(); path.moveTo(245,250); path.cubicTo(135,330,215,520,415,565);
        path.cubicTo(515,585,560,500,505,390); path.cubicTo(450,300,335,235,245,250);
        canvas.drawPath(path,p); p.setShader(null);
        paint(0xFFDCE5EF,15f,Paint.Style.STROKE);
        path.reset(); path.moveTo(245,250); path.cubicTo(135,330,215,520,415,565);
        path.cubicTo(515,585,560,500,505,390); canvas.drawPath(path,p);
        paint(0xFF52677D,8f,Paint.Style.STROKE);
        canvas.drawLine(245,250,505,390,p); canvas.drawLine(415,565,505,390,p);
        canvas.drawLine(315,430,465,300,p);
        // Bras d'alimentation et tête LNB.
        paint(0xFFB7C4D2,19f,Paint.Style.STROKE); canvas.drawLine(315,430,465,300,p);
        p.setShader(new LinearGradient(440,285,495,330,0xFFFFFFFF,0xFF687D93,Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(438,286,495,330),13,13,p); p.setShader(null);
        // Mât, articulation et socle, visibles en entier.
        paint(0xFF8D9EAF,22f,Paint.Style.STROKE); canvas.drawLine(310,525,310,640,p);
        canvas.drawLine(310,555,255,650,p); canvas.drawLine(310,555,365,650,p);
        p.setShader(new LinearGradient(225,635,390,665,0xFFE6EDF4,0xFF63768B,Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(220,642,400,668),10,10,p); p.setShader(null);
        paint(0xFFE5EDF5,5f,Paint.Style.STROKE); canvas.drawLine(245,650,375,650,p);

        // Mot-symbole sur une seule ligne : le v minuscule touche le T.
        paint(Color.WHITE,0f,Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC)); p.setTextSize(370f);
        p.setShadowLayer(18f,0f,7f,0xAAFFFFFF); canvas.drawText("MT",textStart,585f,p); p.clearShadowLayer();
        paint(0xFFFFC21F,0f,Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD_ITALIC)); p.setTextSize(370f);
        p.setShadowLayer(22f,0f,5f,0xFFFFA900); canvas.drawText("v",vStart,585f,p); p.clearShadowLayer();

        // Aucun trait décoratif sous la parabole : la seule liaison est celle vers le satellite.
        canvas.restore();
    }
}
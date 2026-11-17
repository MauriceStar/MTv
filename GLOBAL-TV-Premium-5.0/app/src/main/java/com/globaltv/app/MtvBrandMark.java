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

/** Logo MTv : parabole, signal satellite et trajectoire orbitale. */
public final class MtvBrandMark extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public MtvBrandMark(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Logo MTv avec parabole et satellite");
    }

    private void paint(int color, float width, Paint.Style style) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStyle(style);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 1774f, getHeight() / 887f);

        // Trajectoire de transmission en pointillés, au-dessus du mot-symbole.
        paint(0xFFFFC52B, 13f, Paint.Style.STROKE);
        p.setPathEffect(new DashPathEffect(new float[]{34f, 24f}, 0f));
        path.reset();
        path.moveTo(455f, 285f);
        path.cubicTo(720f, 40f, 1120f, 10f, 1370f, 135f);
        canvas.drawPath(path, p);
        p.setPathEffect(null);

        // Satellite et panneaux solaires.
        canvas.save();
        canvas.rotate(18f, 1380f, 130f);
        paint(0xFF2865A8, 8f, Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(1190f, 65f, 1318f, 188f), 8f, 8f, p);
        canvas.drawRoundRect(new RectF(1450f, 65f, 1578f, 188f), 8f, 8f, p);
        paint(0xFF78B9F0, 3f, Paint.Style.STROKE);
        for (int i = 1; i < 4; i++) {
            canvas.drawLine(1190f + i*32f, 65f, 1190f + i*32f, 188f, p);
            canvas.drawLine(1450f + i*32f, 65f, 1450f + i*32f, 188f, p);
        }
        canvas.drawLine(1190f, 126f, 1318f, 126f, p);
        canvas.drawLine(1450f, 126f, 1578f, 126f, p);
        paint(0xFFBFC9D6, 5f, Paint.Style.STROKE);
        canvas.drawLine(1318f, 126f, 1342f, 126f, p);
        canvas.drawLine(1418f, 126f, 1450f, 126f, p);
        paint(0xFFCED8E2, 4f, Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(1340f, 76f, 1420f, 180f), 25f, 25f, p);
        canvas.drawOval(new RectF(1350f, 55f, 1410f, 91f), p);
        paint(0xFFFFC52B, 12f, Paint.Style.FILL);
        canvas.drawCircle(1380f, 191f, 10f, p);
        canvas.restore();

        // Ondes émises par le foyer de la parabole.
        paint(0xFFFFB52E, 12f, Paint.Style.STROKE);
        canvas.drawArc(new RectF(350f, 205f, 530f, 430f), -72f, 144f, false, p);
        canvas.drawArc(new RectF(310f, 175f, 575f, 470f), -72f, 144f, false, p);
        canvas.drawArc(new RectF(270f, 145f, 620f, 510f), -72f, 144f, false, p);

        // Parabole métallique : réflecteur, bras et tête LNB.
        paint(0xFFB9C5D2, 16f, Paint.Style.STROKE);
        path.reset();
        path.moveTo(245f, 265f);
        path.cubicTo(145f, 330f, 225f, 520f, 420f, 565f);
        path.cubicTo(520f, 590f, 555f, 505f, 510f, 410f);
        canvas.drawPath(path, p);
        paint(0xFFE5EBF2, 5f, Paint.Style.STROKE);
        path.reset();
        path.moveTo(257f, 278f);
        path.cubicTo(180f, 350f, 255f, 500f, 420f, 548f);
        canvas.drawPath(path, p);
        paint(0xFF66788C, 9f, Paint.Style.STROKE);
        canvas.drawLine(315f, 430f, 445f, 330f, p);
        canvas.drawLine(445f, 330f, 465f, 300f, p);
        paint(0xFFD9E1EA, 18f, Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(438f, 290f, 490f, 326f), 12f, 12f, p);
        paint(0xFF7D8DA0, 13f, Paint.Style.STROKE);
        canvas.drawLine(305f, 520f, 265f, 640f, p);
        canvas.drawLine(305f, 520f, 355f, 640f, p);
        paint(0xFFB8C4D1, 10f, Paint.Style.STROKE);
        canvas.drawLine(235f, 650f, 390f, 650f, p);
        canvas.drawLine(265f, 640f, 355f, 640f, p);

        // Signature MTv sur une seule ligne.
        paint(Color.WHITE, 0f, Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        p.setTextSize(370f);
        p.setShadowLayer(18f, 0f, 7f, 0xAAFFFFFF);
        canvas.drawText("MT", 545f, 585f, p);
        p.clearShadowLayer();
        paint(0xFFFFC21F, 0f, Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        p.setTextSize(370f);
        p.setShadowLayer(22f, 0f, 5f, 0xFFFFA900);
        canvas.drawText("v", 1190f, 585f, p);
        p.clearShadowLayer();

        // Liseré orbital doré sous le logo.
        paint(0xFFFFB51E, 15f, Paint.Style.STROKE);
        path.reset();
        path.moveTo(110f, 620f);
        path.cubicTo(330f, 790f, 920f, 720f, 1190f, 600f);
        canvas.drawPath(path, p);
        canvas.restore();
    }
}

package com.mobilegis.cropwatcher.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class CustomChartView extends View {
    private List<Float> dataPoints = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    
    private Paint gridPaint;
    private Paint linePaint;
    private Paint fillPaint;
    private Paint pointPaint;
    private Paint textPaint;
    
    private float paddingLeft = 80f;
    private float paddingRight = 40f;
    private float paddingTop = 40f;
    private float paddingBottom = 60f;

    public CustomChartView(Context context) {
        super(context);
        init();
    }

    public CustomChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#E0E0E0"));
        gridPaint.setStrokeWidth(2f);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#1B5E20")); // Forest Green
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(6f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.parseColor("#4CAF50"));
        pointPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#757575"));
        textPaint.setTextSize(24f);
        textPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setData(List<Float> points, List<String> xLabels) {
        this.dataPoints = points != null ? points : new ArrayList<>();
        this.labels = xLabels != null ? xLabels : new ArrayList<>();
        invalidate(); // Redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float width = getWidth();
        float height = getHeight();
        
        float chartWidth = width - paddingLeft - paddingRight;
        float chartHeight = height - paddingTop - paddingBottom;

        // Draw Y Axis Labels and Horizontal Grid Lines (5 divisions)
        int divisions = 5;
        for (int i = 0; i <= divisions; i++) {
            float ratio = (float) i / divisions;
            float y = height - paddingBottom - (ratio * chartHeight);
            
            // Draw grid line
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint);
            
            // Draw text value label
            float val = ratio * 1.0f; // Scale is 0.0 to 1.0
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format("%.2f", val), paddingLeft - 15f, y + 8f, textPaint);
        }

        if (dataPoints.isEmpty()) {
            // No data placeholder text
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Chưa có dữ liệu thống kê", width / 2f, height / 2f, textPaint);
            return;
        }

        // Calculate step sizes
        float xStep = dataPoints.size() > 1 ? chartWidth / (dataPoints.size() - 1) : chartWidth;

        // Create Path for Line and Fill
        Path linePath = new Path();
        Path fillPath = new Path();
        
        float firstX = paddingLeft;
        float firstY = height - paddingBottom - (Math.max(0f, Math.min(1f, dataPoints.get(0))) * chartHeight);
        
        linePath.moveTo(firstX, firstY);
        fillPath.moveTo(firstX, height - paddingBottom);
        fillPath.lineTo(firstX, firstY);

        for (int i = 1; i < dataPoints.size(); i++) {
            float val = dataPoints.get(i);
            // Clamp value between 0.0 and 1.0
            val = Math.max(0f, Math.min(1f, val));
            
            float cx = paddingLeft + (i * xStep);
            float cy = height - paddingBottom - (val * chartHeight);
            
            linePath.lineTo(cx, cy);
            fillPath.lineTo(cx, cy);
        }

        // Close fill path
        float lastX = paddingLeft + ((dataPoints.size() - 1) * xStep);
        fillPath.lineTo(lastX, height - paddingBottom);
        fillPath.close();

        // Apply Gradient to Fill
        int startColor = Color.parseColor("#444CAF50"); // Green transparent
        int endColor = Color.parseColor("#004CAF50");
        Shader shader = new LinearGradient(0, paddingTop, 0, height - paddingBottom, startColor, endColor, Shader.TileMode.CLAMP);
        fillPaint.setShader(shader);
        
        // Draw fill and line
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Draw points and labels
        for (int i = 0; i < dataPoints.size(); i++) {
            float val = dataPoints.get(i);
            val = Math.max(0f, Math.min(1f, val));
            
            float cx = paddingLeft + (i * xStep);
            float cy = height - paddingBottom - (val * chartHeight);

            // Draw point circle
            canvas.drawCircle(cx, cy, 10f, pointPaint);
            
            // Draw white center dot for premium look
            Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            whitePaint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, 4f, whitePaint);

            // Draw X Label
            if (i < labels.size()) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(labels.get(i), cx, height - 10f, textPaint);
            }
        }
    }
}

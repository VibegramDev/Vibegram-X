/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

import android.graphics.Matrix;
import android.graphics.Path;

import androidx.graphics.shapes.CornerRounding;
import androidx.graphics.shapes.Morph;
import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.RoundedPolygonKt;
import androidx.graphics.shapes.ShapesKt;
import androidx.graphics.shapes.Shapes_androidKt;

import java.util.ArrayList;
import java.util.List;

/**
 * androidx.graphics.shapes implementation of {@link M3LoadingIndicator.Shapes}: the indeterminate polygon sequence
 * of compose {@code LoadingIndicatorDefaults.IndeterminateIndicatorPolygons}, with the vertex data of compose
 * {@code MaterialShapes} (SoftBurst, Cookie9Sided, Pentagon, Pill, Sunny, Cookie4Sided, Oval).
 */
public final class M3LoadingShapes {
  private M3LoadingShapes () { }

  public static M3LoadingIndicator.Shapes create () {
    return new Impl();
  }

  private static final class Impl implements M3LoadingIndicator.Shapes {
    private final Morph[] morphs;
    private final float scaleFactor;

    Impl () {
      RoundedPolygon[] polygons = {softBurst(), cookie9(), pentagon(), pill(), sunny(), cookie4(), oval()};
      for (int i = 0; i < polygons.length; i++) {
        polygons[i] = polygons[i].normalized();
      }
      scaleFactor = calculateScaleFactor(polygons) * M3LoadingIndicator.ACTIVE_SIZE / M3LoadingIndicator.CONTAINER_SIZE;
      morphs = new Morph[polygons.length];
      for (int i = 0; i < polygons.length; i++) {
        morphs[i] = new Morph(polygons[i], polygons[(i + 1) % polygons.length]);
      }
    }

    @Override
    public int count () {
      return morphs.length;
    }

    @Override
    public void toPath (int index, float progress, Path out) {
      out.rewind();
      Shapes_androidKt.toPath(morphs[index], progress, out);
    }

    @Override
    public float scaleFactor () {
      return scaleFactor;
    }
  }

  /** Bounds vs. max (rotating) bounds, as compose calculateScaleFactor: shapes never clip while they rotate. */
  private static float calculateScaleFactor (RoundedPolygon[] polygons) {
    float scaleFactor = 1f;
    float[] bounds = new float[4], maxBounds = new float[4];
    for (RoundedPolygon polygon : polygons) {
      polygon.calculateBounds(bounds);
      polygon.calculateMaxBounds(maxBounds);
      float scaleX = (bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0]);
      float scaleY = (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1]);
      scaleFactor = Math.min(scaleFactor, Math.max(scaleX, scaleY));
    }
    return scaleFactor;
  }

  private static CornerRounding round (float radius) {
    return new CornerRounding(radius, 0f);
  }

  private static Matrix rotation (float degrees) {
    Matrix matrix = new Matrix();
    matrix.setRotate(degrees);
    return matrix;
  }

  private static RoundedPolygon softBurst () {
    return customPolygon(new float[][] {{0.193f, 0.277f, 0.053f}, {0.176f, 0.055f, 0.053f}}, 10, false);
  }

  private static RoundedPolygon cookie9 () {
    RoundedPolygon star = ShapesKt.star(RoundedPolygon.Companion, 9, 1f, .8f, round(.5f));
    return Shapes_androidKt.transformed(star, rotation(-90f));
  }

  private static RoundedPolygon pentagon () {
    return customPolygon(new float[][] {{0.500f, -0.009f, 0.172f}, {1.030f, 0.365f, 0.164f}, {0.828f, 0.970f, 0.169f}}, 1, true);
  }

  private static RoundedPolygon pill () {
    return customPolygon(new float[][] {{0.961f, 0.039f, 0.426f}, {1.001f, 0.428f, 0f}, {1.000f, 0.609f, 1.000f}}, 2, true);
  }

  private static RoundedPolygon sunny () {
    return ShapesKt.star(RoundedPolygon.Companion, 8, 1f, .8f, round(.15f));
  }

  private static RoundedPolygon cookie4 () {
    return customPolygon(new float[][] {{1.237f, 1.236f, 0.258f}, {0.500f, 0.918f, 0.233f}}, 4, false);
  }

  private static RoundedPolygon oval () {
    Matrix scale = new Matrix();
    scale.setScale(1f, .64f);
    RoundedPolygon circle = ShapesKt.circle(RoundedPolygon.Companion);
    return Shapes_androidKt.transformed(Shapes_androidKt.transformed(circle, scale), rotation(-45f));
  }

  /** Port of compose MaterialShapes.customPolygon / doRepeat; points are {x, y, cornerRadius}, center (0.5, 0.5). */
  private static RoundedPolygon customPolygon (float[][] points, int reps, boolean mirroring) {
    final float cx = .5f, cy = .5f;
    List<float[]> actual = new ArrayList<>();
    if (mirroring) {
      int n = points.length;
      float[] angles = new float[n], distances = new float[n];
      for (int i = 0; i < n; i++) {
        float dx = points[i][0] - cx, dy = points[i][1] - cy;
        angles[i] = (float) Math.toDegrees(Math.atan2(dy, dx));
        distances[i] = (float) Math.hypot(dx, dy);
      }
      int actualReps = reps * 2;
      float sectionAngle = 360f / actualReps;
      for (int it = 0; it < actualReps; it++) {
        for (int index = 0; index < n; index++) {
          int i = it % 2 == 0 ? index : n - 1 - index;
          if (i > 0 || it % 2 == 0) {
            float degrees = sectionAngle * it + (it % 2 == 0 ? angles[i] : sectionAngle - angles[i] + 2 * angles[0]);
            double a = Math.toRadians(degrees);
            actual.add(new float[] {(float) Math.cos(a) * distances[i] + cx, (float) Math.sin(a) * distances[i] + cy, points[i][2]});
          }
        }
      }
    } else {
      int np = points.length;
      for (int it = 0; it < np * reps; it++) {
        float[] p = points[it % np];
        double a = Math.toRadians((it / np) * 360f / reps);
        float dx = p[0] - cx, dy = p[1] - cy;
        actual.add(new float[] {(float) (dx * Math.cos(a) - dy * Math.sin(a)) + cx, (float) (dx * Math.sin(a) + dy * Math.cos(a)) + cy, p[2]});
      }
    }
    float[] vertices = new float[actual.size() * 2];
    List<CornerRounding> rounding = new ArrayList<>(actual.size());
    for (int i = 0; i < actual.size(); i++) {
      float[] p = actual.get(i);
      vertices[i * 2] = p[0];
      vertices[i * 2 + 1] = p[1];
      rounding.add(p[2] > 0f ? round(p[2]) : CornerRounding.Unrounded);
    }
    return RoundedPolygonKt.RoundedPolygon(vertices, CornerRounding.Unrounded, rounding, cx, cy);
  }
}

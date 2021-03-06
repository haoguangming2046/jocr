package com.eriqaugustine.ocr.image;

import com.eriqaugustine.ocr.utils.ImageUtils;
import com.eriqaugustine.ocr.utils.ListUtils;
import com.eriqaugustine.ocr.utils.MathUtils;
import com.eriqaugustine.ocr.utils.Props;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Namespace for images that contain a single character.
 */
public class CharacterImage {
   private static final int DEFAULT_POINT_SIZE = 2;

   private static final double DEFAULT_POINT_DENSITY = 0.75;

   private static final double DEFAULT_OVERLAP_PERCENT = 0.50;

   private static final int DEFAULT_MIN_LINE_SLICES = 3;

   /**
    * A line must cover at least this percentage of slices ine its respective direction to not
    * be pruned.
    */
   private static final double DEFAULT_LINE_SLICE_PERCENTAGE = 0.10;

   private static void drawLines(List<Line> lines, byte[] image, int imageWidth) {
      for (Line line : lines) {
         for (int i = 0; i < line.pieces.size(); i++) {
            SlicePiece piece = line.pieces.get(i);

            for (int j = piece.start; j <= piece.end; j++) {
               int row = line.horizontal ? j : line.start + i;
               int col = line.horizontal ? line.start + i : j;

               int index = MathUtils.rowColToIndex(row, col, imageWidth);
               image[index] = 0;
            }
         }
      }
   }

   private static void singlePathLines(List<Line> lines) {
      for (Line line : lines) {
         line.singlePath();
      }
   }

   /**
    * Prune invalid lines from a list of lines.
    * Rules:
    *  - Lines must cover at least |minSlices|.
    */
   private static void pruneLines(List<Line> lines, int minSlices) {
      List<Integer> toRemove = new ArrayList<Integer>();

      for (int i = 0; i < lines.size(); i++) {
         Line line = lines.get(i);

         if (line.pieces.size() < minSlices) {
            toRemove.add(new Integer(i));
         }
      }

      for (int removeIndex = toRemove.size() - 1; removeIndex >= 0; removeIndex--) {
         lines.remove(toRemove.get(removeIndex).intValue());
      }
   }

   private static void pruneLines(List<Line> lines) {
      pruneLines(lines, DEFAULT_MIN_LINE_SLICES);
   }

   public static List<Line> getLines(byte[] pixels, int imageWidth, boolean horizontalLines) {
      // Note: Need to make vertical slices to get horizontal lines and visa-versa.
      Slice[] slices = characterSlices(pixels, imageWidth, !horizontalLines);
      return slicesToLines(slices, !horizontalLines);
   }

   public static List<Line> slicesToLines(Slice[] slices, boolean horizontalSlices) {
      return slicesToLines(slices, horizontalSlices, DEFAULT_OVERLAP_PERCENT);
   }

   public static List<Line> slicesToLines(Slice[] slices,
                                          boolean horizontalSlice,
                                          double overlapPercent) {
      List<Line> lines = new ArrayList<Line>();

      // Lines that are not yet complete.
      // A line becomes complete either at the end, or when it was not used in a slice.
      List<Line> lineParts = new ArrayList<Line>();

      // The index of the line parts that have been unsed.
      Set<Integer> usedLineParts = new HashSet<Integer>();

      // The line parts that were added in the current slice.
      // These are NOT parts that were appended to.
      List<Line> newLineParts = new ArrayList<Line>();

      for (int i = 0; i < slices.length; i++) {
         for (SlicePiece slicePiece : slices[i].pieces) {
            if (i == 0) {
               newLineParts.add(new Line(i, !horizontalSlice, slicePiece));
            } else {
               boolean append = false;

               for (int linePartIndex = 0; linePartIndex < lineParts.size(); linePartIndex++) {
                  Line linePart = lineParts.get(linePartIndex);

                  if (absoluteOverlapPercent(slicePiece,
                                             linePart.getLastPiece()) >= overlapPercent) {
                     linePart.add(slicePiece, i);
                     usedLineParts.add(new Integer(linePartIndex));
                     append = true;
                     break;
                  }
               }

               if (!append) {
                  newLineParts.add(new Line(i, !horizontalSlice, slicePiece));
               }
            }
         }

         // Remove all the unused line parts from the list.
         // Must be kept in order.
         List<Integer> toRemove = new ArrayList<Integer>();
         for (int j = 0; j < lineParts.size(); j++) {
            if (!usedLineParts.contains(new Integer(j))) {
               toRemove.add(new Integer(j));
            }
         }

         for (int j = toRemove.size() - 1; j >= 0; j--) {
            lines.add(lineParts.remove(toRemove.get(j).intValue()));
         }

         // Move the new line parts to the formal list.
         lineParts.addAll(newLineParts);
         newLineParts.clear();
      }

      // Add all the final line parts.
      lines.addAll(lineParts);

      return lines;
   }

   private static double absoluteOverlapPercent(SlicePiece a, SlicePiece b) {
      return absoluteOverlapPercent(a.bounds(), b.bounds());
   }

   /**
    * Given two bounds ([start, end]), give the absolute percentage that they overlap.
    * For the ratio, the larger of the two bounds will be used.
    */
   private static double absoluteOverlapPercent(int[] a, int[] b) {
      assert(a[0] < a[1]);
      assert(b[0] < b[1]);

      int count = 0;
      for (int i = a[0]; i <= a[1]; i++) {
         if (i >= b[0] && i <= b[1]) {
            count++;
         }
      }

      return (double)count / Math.max(a[1] - a[0] + 1, b[1] - b[0] + 1);
   }

   /**
    * Take an image of a character, and slice that image horizontally or vertically.
    */
   private static Slice[] characterSlices(byte[] pixels, int imageWidth,
                                          boolean horizontal) {
      int outerEnd = horizontal ? pixels.length / imageWidth : imageWidth;
      int innerEnd = horizontal ? imageWidth : pixels.length / imageWidth;

      Slice[] slices = new Slice[outerEnd];

      for (int i = 0; i < outerEnd; i++) {
         slices[i] = new Slice(horizontal);

         int boundStart = -1;
         for (int j = 0; j < innerEnd; j++) {
            int index = horizontal ? MathUtils.rowColToIndex(i, j, imageWidth) :
                                     MathUtils.rowColToIndex(j, i, imageWidth);

            if ((0xFF & pixels[index]) == 0 && boundStart == -1) {
               boundStart = j;
            } else if ((0xFF & pixels[index]) != 0 && boundStart != -1) {
               slices[i].pieces.add(new SlicePiece(boundStart, j - 1));
               boundStart = -1;
            }
         }

         if (boundStart != -1) {
            slices[i].pieces.add(new SlicePiece(boundStart, innerEnd - 1));
         }
      }

      return slices;
   }

   /**
    * Does the same as discretizeLines(), except the resulting image is the same size.
    */
   public static boolean[] boxizeLines(byte[] pixels, int imageWidth,
                                       int pointSize, double pointDensity) {
      boolean[] points = new boolean[pixels.length];

      for (int row = 0; row < pixels.length / imageWidth; row++) {
         for (int col = 0; col < imageWidth; col++) {
            int pixelCount = 0;

            for (int pointRowOffset = 0; pointRowOffset < pointSize; pointRowOffset++) {
               for (int pointColOffset = 0; pointColOffset < pointSize; pointColOffset++) {
                  int index = MathUtils.rowColToIndex(row + pointRowOffset,
                                                      col + pointColOffset,
                                                      imageWidth);

                  if (index < pixels.length && pixels[index] == 0) {
                     pixelCount++;
                  }
               }
            }

            if (pixelCount / (double)(pointSize * pointSize) >= pointDensity) {
               points[MathUtils.rowColToIndex(row, col, imageWidth)] = true;
            }
         }
      }

      return points;
   }

   public static boolean[] boxizeLines(byte[] pixels, int imageWidth) {
      return boxizeLines(pixels, imageWidth, DEFAULT_POINT_SIZE, DEFAULT_POINT_DENSITY);
   }

   /**
    * Get the outlines for an image, only border pixels willbe shown.
    * |pixels| is assumed to be bw.
    */
   public static boolean[] getOutline(byte[] pixels, int imageWidth) {
      boolean[] outline = new boolean[pixels.length];
      int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

      for (int row = 0; row < pixels.length / imageWidth; row++) {
         for (int col = 0; col < imageWidth; col++) {
            int index = MathUtils.rowColToIndex(row, col, imageWidth);
            boolean border = false;

            // Only consider occupied pixels.
            if ((pixels[index] & 0xFF) == 0xFF) {
               continue;
            }

            // Any pixel touching an edge is automatically a border.
            if (row == 0 || row == pixels.length / imageWidth - 1 ||
                col == 0 || col == imageWidth - 1) {
               border = true;
            } else {
               for (int[] neighborOffset : neighborOffsets) {
                  int newRow = row + neighborOffset[0];
                  int newCol = col + neighborOffset[1];
                  int newIndex = MathUtils.rowColToIndex(newRow, newCol, imageWidth);

                  // Not enough to check index bounds because it could be on vertical edge.
                  // If the pixel touches any whitespace, it is a border.
                  if (newRow >= 0 && newRow < pixels.length / imageWidth &&
                      newCol >= 0 && newCol < imageWidth &&
                      (pixels[newIndex] & 0xFF) == 0xFF) {
                     border = true;
                     break;
                  }
               }
            }

            if (border) {
               outline[index] = true;
            }
         }
      }

      return outline;
   }

   /**
    * Turns |pixels| into a more defined set of points.
    * A point can be a single pixel, or a box of pixels.
    * Assumes that |pixels| is bw.
    * |pointSize| is the length of one of the sides of the box.
    * The resulting image will be
    * (|imageWidth| / |pointSize|) x (|pixels|.length / |imageWidth| / |pointSize|)
    */
   public static boolean[] discretizeLines(byte[] pixels, int imageWidth,
                                           int pointSize, double pointDensity) {
      int newWidth = imageWidth / pointSize;
      int newHeight = pixels.length / imageWidth / pointSize;

      boolean[] points = new boolean[newWidth * newHeight];

      for (int row = 0; row < newHeight; row++) {
         for (int col = 0; col < newWidth; col++) {
            int pixelCount = 0;

            for (int pointRowOffset = 0; pointRowOffset < pointSize; pointRowOffset++) {
               for (int pointColOffset = 0; pointColOffset < pointSize; pointColOffset++) {
                  int index = MathUtils.rowColToIndex((row * pointSize) + pointRowOffset,
                                                      (col * pointSize) + pointColOffset,
                                                      imageWidth);

                  if (index < pixels.length && pixels[index] == 0) {
                     pixelCount++;
                  }
               }
            }

            if (pixelCount / (double)(pointSize * pointSize) >= pointDensity) {
               points[MathUtils.rowColToIndex(row, col, newWidth)] = true;
            }
         }
      }

      return points;
   }

   public static boolean[] discretizeLines(byte[] pixels, int imageWidth) {
      return discretizeLines(pixels, imageWidth, DEFAULT_POINT_SIZE, DEFAULT_POINT_DENSITY);
   }

   /**
    * Get the distance (DISsimilarity) bewteen two density maps.
    * Distance is currently measured using MSE.
    */
   public static double densityMapDistance(double[][] a, double[][] b) {
      assert(a.length == b.length);

      double sumSquareError = 0;
      int count = 0;

      for (int i = 0; i < a.length; i++) {
         assert(a[i].length == b[i].length);

         for (int j = 0; j < a[i].length; j++) {
            sumSquareError += Math.pow(a[i][j] - b[i][j], 2);
            count++;
         }
      }

      return sumSquareError / count;
   }

   /**
    * Get the density of the different regions of the character.
    * Note: Because pixels are atomic, some pixels on the right and bottom edges may be lost.
    *  The alternative to losing pixels would be to have uneven regions.
    */
   public static double[][] getDensityMap(WrapImage image,
                                          int rows, int cols,
                                          int whiteThreshold) {
      assert(rows > 0 && cols > 0);

      double[][] densityMap = new double[rows][cols];

      boolean[] pixels = image.getDiscretePixels();

      int rowDelta = image.height() / rows;
      int colDelta = image.width() / cols;

      if (rowDelta == 0 || colDelta == 0) {
         return null;
      }

      for (int row = 0; row < rows; row++) {
         for (int col = 0; col < cols; col++) {
            densityMap[row][col] =
               ImageUtils.density(pixels, image.width(),
                                  row * rowDelta, rowDelta,
                                  col * colDelta, colDelta,
                                  whiteThreshold);
         }
      }

      return densityMap;
   }

   public static double[][] getDensityMap(WrapImage image,
                                          int rows, int cols) {
      return getDensityMap(image, rows, cols, Props.getInt("DEFAULT_WHITE_THRESHOLD"));
   }

   /**
    * Generate an image for every character in the string.
    * The index of the entry represents the character associated with it.
    */
   public static WrapImage[] generateFontImages(String characters, String font) {
      WrapImage[] images = new WrapImage[characters.length()];

      for (int i = 0; i < characters.length(); i++) {
         images[i] = WrapImage.getCharacterImage(characters.charAt(i), true, font);
      }

      return images;
   }

   public static WrapImage[] generateFontImages(String characters) {
      return generateFontImages(characters, Props.getString("DEFAULT_FONT_FAMILY", "IPAGothic"));
   }

   public static WrapImage[] generateFontImages(String characters, String[] fonts) {
      WrapImage[] rtn = new WrapImage[characters.length() * fonts.length];

      for (int i = 0; i < fonts.length; i++) {
         WrapImage[] images = generateFontImages(characters, fonts[i]);
         ListUtils.fill(rtn, images, i * characters.length());
      }

      return rtn;
   }

   /**
    * Get the density maps for the output of generateFontImages().
    */
   public static double[][][] getFontDensityMaps(String characters,
                                                 int mapRows,
                                                 int mapCols) {
      return getFontDensityMaps(
         characters,
         mapRows,
         mapCols,
         new String[]{Props.getString("DEFAULT_FONT_FAMILY")});
   }

   public static double[][][] getFontDensityMaps(String characters,
                                                 int mapRows,
                                                 int mapCols,
                                                 String[] fonts) {
      WrapImage[] characterImages = generateFontImages(characters, fonts);
      double[][][] maps = new double[characterImages.length][][];

      for (int i = 0; i < characterImages.length; i++) {
         maps[i] = getDensityMap(characterImages[i], mapRows, mapCols);
      }

      return maps;
   }

   /**
    * A connected line of pixels.
    * This is esentially a specialized Blob.
    */
   public static class Line {
      public List<SlicePiece> pieces;
      public boolean horizontal;

      public int start;
      public int end;

      public Line(int start, boolean horizontal, SlicePiece startPiece) {
         this.start = start;
         this.end = start;

         this.horizontal = horizontal;

         pieces = new ArrayList<SlicePiece>();
         pieces.add(startPiece);
      }

      // HACK(eriq): This is sloppy handling of the index.
      public void add(SlicePiece piece, int index) {
         pieces.add(piece);
         end = index;
      }

      public SlicePiece getLastPiece() {
         return pieces.get(pieces.size() - 1);
      }

      /**
       * Thin the Line and ensure that there is only a single wide path
       *  on the line.
       * Thin all even slices, and then use the odd slices as bridges.
       */
      public void singlePath() {
         // First thin each even piece.
         for (int i = 0; i < pieces.size(); i += 2) {
            pieces.get(i).thin();
         }

         for (int i = 1; i < pieces.size(); i += 2) {
            if (i == pieces.size() - 1) {
               pieces.get(i).thin();
               pieces.get(i).extend(pieces.get(i - 1).start);
            } else {
               pieces.get(i).start = Math.min(pieces.get(i - 1).start,
                                              pieces.get(i + 1).start);
               pieces.get(i).end = Math.max(pieces.get(i - 1).start,
                                            pieces.get(i + 1).start);
            }
         }
      }
   }

   /**
    * A single horizontal or vertical slice.
    */
   public static class Slice {
      public List<SlicePiece> pieces;
      public boolean horizontal;

      public Slice(boolean horizontal) {
         this.horizontal = horizontal;
         pieces = new ArrayList<SlicePiece>();
      }
   }

   /**
    * A piece of a slice denoting the start and end of pixels.
    */
   public static class SlicePiece {
      public int start;
      public int end;

      public SlicePiece(int start, int end) {
         this.start = start;
         this.end = end;
      }

      public int[] bounds() {
         return new int[]{start, end};
      }

      /**
       * Thin the piece to a single point.
       */
      public void thin() {
         int point = start + ((end - start) / 2);
         start = point;
         end = point;
      }

      public void extend(int index) {
         if (index < start) {
            start = index;
         } else if (index > end) {
            end = index;
         }
      }
   }
}

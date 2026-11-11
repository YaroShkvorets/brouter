package btools.router;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import btools.mapaccess.GeometryDecoder;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;

/**
 * GeoJSON for an isochrone: the way sections reached, as one MultiLineString per
 * minute of travel (property "minutes": reached within that many minutes).
 * Sections that run past the time limit are cut where the limit is reached.
 */
final class FormatIsochrone {

  static final class Section {
    OsmLink link;
    boolean fromStart;
    int[] ilon;
    int[] ilat;
    float t0;
    float t1;
  }

  private FormatIsochrone() {
  }

  /**
   * The section's points from source to target. A section that starts at the
   * waypoint (startPoint not null) begins there instead of at its source node.
   */
  static Section section(OsmLink link, OsmNode source, OsmNode target, OsmNode startPoint, float t0, float t1, GeometryDecoder decoder) {
    int n = 2;
    // The decoder reuses its nodes, so they are copied out straight away.
    OsmTransferNode first = link.geometry == null ? null : decoder.decodeGeometry(link.geometry, source, target, link.isReverse(source));
    for (OsmTransferNode tn = first; tn != null; tn = tn.next) n++;
    int[] lons = new int[n];
    int[] lats = new int[n];
    lons[0] = source.ilon;
    lats[0] = source.ilat;
    int i = 1;
    for (OsmTransferNode tn = first; tn != null; tn = tn.next, i++) {
      lons[i] = tn.ilon;
      lats[i] = tn.ilat;
    }
    lons[n - 1] = target.ilon;
    lats[n - 1] = target.ilat;

    Section s = new Section();
    s.link = link;
    s.fromStart = startPoint != null;
    s.t0 = t0;
    s.t1 = t1;
    if (startPoint == null) {
      s.ilon = lons;
      s.ilat = lats;
      return s;
    }
    int from = nearestSegment(lons, lats, startPoint.ilon, startPoint.ilat);
    s.ilon = new int[n - from];
    s.ilat = new int[n - from];
    s.ilon[0] = startPoint.ilon;
    s.ilat[0] = startPoint.ilat;
    System.arraycopy(lons, from + 1, s.ilon, 1, n - from - 1);
    System.arraycopy(lats, from + 1, s.ilat, 1, n - from - 1);
    return s;
  }

  /** Index of the segment (points i, i+1) closest to the given point. */
  private static int nearestSegment(int[] lons, int[] lats, int lon, int lat) {
    double kx = Math.cos(Math.toRadians((lat - 90000000) / 1e6));
    int best = 0;
    double bestDist = Double.MAX_VALUE;
    for (int i = 0; i + 1 < lons.length; i++) {
      double ax = (lons[i] - lon) * kx, ay = lats[i] - lat;
      double bx = (lons[i + 1] - lon) * kx, by = lats[i + 1] - lat;
      double dx = bx - ax, dy = by - ay;
      double len2 = dx * dx + dy * dy;
      double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
      double px = ax + t * dx, py = ay + t * dy;
      double d = px * px + py * py;
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    return best;
  }

  static String format(List<Section> sections, OsmNode start, double maxTime) {
    // A section reached whole from both ends is kept once, with the earlier time.
    // Links are told apart by identity: OsmNode, a subclass, compares by position.
    Map<OsmLink, Section> whole = new IdentityHashMap<>();
    for (Section s : sections) {
      if (s.fromStart || s.t1 > maxTime) continue;
      Section other = whole.get(s.link);
      if (other == null || s.t1 < other.t1) whole.put(s.link, s);
    }

    int nBands = (int) Math.ceil(maxTime / 60.);
    List<List<int[][]>> bands = new ArrayList<>();
    for (int b = 0; b < nBands; b++) bands.add(new ArrayList<int[][]>());
    for (Section s : sections) {
      if (!s.fromStart && s.t1 <= maxTime && whole.get(s.link) != s) continue;
      int[][] line = s.t1 > maxTime ? cut(s, maxTime) : new int[][]{s.ilon, s.ilat};
      if (line[0].length < 2) continue;
      float t1 = (float) Math.min(s.t1, maxTime);
      int band = Math.max(0, (int) Math.ceil(t1 / 60.) - 1);
      bands.get(Math.min(band, nBands - 1)).add(line);
    }

    StringBuilder sb = new StringBuilder(64 + sections.size() * 40);
    sb.append("{\"type\":\"FeatureCollection\",\"properties\":{\"maxTime\":").append((int) maxTime).append(",\"start\":");
    appendPoint(sb, start.ilon, start.ilat);
    sb.append("},\"features\":[");
    boolean firstFeature = true;
    for (int b = 0; b < nBands; b++) {
      List<int[][]> lines = bands.get(b);
      if (lines.isEmpty()) continue;
      if (!firstFeature) sb.append(',');
      firstFeature = false;
      sb.append("{\"type\":\"Feature\",\"properties\":{\"minutes\":").append(b + 1)
        .append("},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[");
      for (int l = 0; l < lines.size(); l++) {
        if (l > 0) sb.append(',');
        int[] lons = lines.get(l)[0];
        int[] lats = lines.get(l)[1];
        sb.append('[');
        for (int i = 0; i < lons.length; i++) {
          if (i > 0) sb.append(',');
          appendPoint(sb, lons[i], lats[i]);
        }
        sb.append(']');
      }
      sb.append("]}}");
    }
    sb.append("]}\n");
    return sb.toString();
  }

  /** The part of a section ridden before the time limit. */
  private static int[][] cut(Section s, double maxTime) {
    int[] lons = s.ilon;
    int[] lats = s.ilat;
    int n = lons.length;
    double fraction = s.t1 > s.t0 ? (maxTime - s.t0) / (s.t1 - s.t0) : 0;
    double kx = Math.cos(Math.toRadians((lats[0] - 90000000) / 1e6));
    double total = 0;
    for (int i = 1; i < n; i++) total += dist(lons, lats, i, kx);
    double goal = total * fraction;
    double walked = 0;
    int i = 1;
    for (; i < n - 1; i++) {
      double d = dist(lons, lats, i, kx);
      if (walked + d >= goal) break;
      walked += d;
    }
    double d = dist(lons, lats, i, kx);
    double f = d > 0 ? Math.min(1, (goal - walked) / d) : 0;
    int[] cutLons = new int[i + 1];
    int[] cutLats = new int[i + 1];
    System.arraycopy(lons, 0, cutLons, 0, i);
    System.arraycopy(lats, 0, cutLats, 0, i);
    cutLons[i] = (int) Math.round(lons[i - 1] + f * (lons[i] - lons[i - 1]));
    cutLats[i] = (int) Math.round(lats[i - 1] + f * (lats[i] - lats[i - 1]));
    return new int[][]{cutLons, cutLats};
  }

  private static double dist(int[] lons, int[] lats, int i, double kx) {
    double dx = (lons[i] - lons[i - 1]) * kx;
    double dy = lats[i] - lats[i - 1];
    return Math.sqrt(dx * dx + dy * dy);
  }

  /** [lon,lat] with 5 decimals (about a metre). */
  private static void appendPoint(StringBuilder sb, int ilon, int ilat) {
    sb.append('[');
    appendCoordinate(sb, ilon - 180000000);
    sb.append(',');
    appendCoordinate(sb, ilat - 90000000);
    sb.append(']');
  }

  private static void appendCoordinate(StringBuilder sb, int micro) {
    long v = Math.round(micro / 10.);
    if (v < 0) {
      sb.append('-');
      v = -v;
    }
    sb.append(v / 100000).append('.');
    String frac = Long.toString(v % 100000);
    for (int i = frac.length(); i < 5; i++) sb.append('0');
    sb.append(frac);
  }
}

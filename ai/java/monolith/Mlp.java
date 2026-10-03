package monolith;

/**
 * Policy/value network inference for S4/S5. Sparse inputs; weights are row-major [in][out].
 * state: h1 = relu(x W1 + b1); h = relu(h1 W2 + b2); value = tanh(h wv[s] + bv[s])
 * action: a1 = relu(xa Wa + ba); z = relu(h Wzh[s] + a1 Wza[s] + bz[s]); logit = z wz[s] + bzz[s]
 * s is the side (0 corp, 1 runner).
 */
public final class Mlp {
  public final int S, D, H, HA, HZ;
  final float[] w1, b1, w2, b2, wa, ba;
  final float[][] wv, bv, wzh, wza, bz, wz, bzz;

  public Mlp(int S, int D, int H, int HA, int HZ, float[] w1, float[] b1, float[] w2, float[] b2,
             float[] wa, float[] ba, float[][] wv, float[][] bv, float[][] wzh, float[][] wza,
             float[][] bz, float[][] wz, float[][] bzz) {
    this.S = S; this.D = D; this.H = H; this.HA = HA; this.HZ = HZ;
    this.w1 = w1; this.b1 = b1; this.w2 = w2; this.b2 = b2; this.wa = wa; this.ba = ba;
    this.wv = wv; this.bv = bv; this.wzh = wzh; this.wza = wza; this.bz = bz; this.wz = wz; this.bzz = bzz;
  }

  static void relu(float[] v) { for (int i = 0; i < v.length; i++) if (v[i] < 0) v[i] = 0; }

  /** Sparse x (idx/val) through a [in][out] matrix plus bias. */
  static float[] sparseLayer(int[] idx, float[] val, float[] w, float[] b, int out) {
    float[] y = b.clone();
    for (int k = 0; k < idx.length; k++) {
      int row = idx[k] * out; float v = val[k];
      for (int j = 0; j < out; j++) y[j] += v * w[row + j];
    }
    return y;
  }

  static float[] denseLayer(float[] x, float[] w, float[] b, int out) {
    float[] y = b.clone();
    for (int i = 0; i < x.length; i++) {
      float v = x[i]; if (v == 0) continue;
      int row = i * out;
      for (int j = 0; j < out; j++) y[j] += v * w[row + j];
    }
    return y;
  }

  public float[] trunk(int[] idx, float[] val) {
    float[] h1 = sparseLayer(idx, val, w1, b1, H); relu(h1);
    float[] h = denseLayer(h1, w2, b2, H); relu(h);
    return h;
  }

  public float value(float[] h, int side) {
    float s = bv[side][0];
    for (int i = 0; i < H; i++) s += h[i] * wv[side][i];
    return (float) Math.tanh(s);
  }

  /** Logits for each action; actions given as parallel sparse arrays. */
  public float[] logits(float[] h, int side, int[][] aidx, float[][] aval) {
    float[] hz = denseLayer(h, wzh[side], bz[side], HZ);
    float[] out = new float[aidx.length];
    for (int a = 0; a < aidx.length; a++) {
      float[] a1 = sparseLayer(aidx[a], aval[a], wa, ba, HA); relu(a1);
      float[] z = hz.clone();
      for (int i = 0; i < HA; i++) {
        float v = a1[i]; if (v == 0) continue;
        int row = i * HZ;
        for (int j = 0; j < HZ; j++) z[j] += v * wza[side][row + j];
      }
      float s = bzz[side][0];
      for (int j = 0; j < HZ; j++) if (z[j] > 0) s += z[j] * wz[side][j];
      out[a] = s;
    }
    return out;
  }
}

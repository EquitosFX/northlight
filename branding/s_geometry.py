import numpy as np

SQ = np.sqrt(0.5)
D = np.array([SQ, SQ]); NV = np.array([SQ, -SQ])   # screen coords, y down

def pattern(dist, u):
    k = dist / u
    return ((k <= 0.5) | ((k >= 1.5) & (k <= 2.5)) | ((k >= 3.5) & (k <= 4.5)))

def half(X, Y, c1, c2, u, cut, notch, sign):
    """Light area from one centre's system. sign=+1 for the top centre, -1 for the bottom (mirrored)."""
    px, py = X - c1[0], Y - c1[1]
    s1 = (px * D[0] + py * D[1]) * sign
    n1 = (px * NV[0] + py * NV[1]) * sign
    r1 = np.hypot(px, py)
    s2 = ((X - c2[0]) * D[0] + (Y - c2[1]) * D[1]) * sign
    xs = X * sign
    circ = (s1 <= 0) & (r1 <= 4.5 * u) & pattern(r1, u)
    lines_shared = (s1 > 0) & (s2 <= 0) & (n1 <= 0.5 * u) & (n1 >= -4.5 * u) & pattern(np.abs(n1), u)
    own = (s1 > 0) & (n1 > 0.5 * u) & (n1 <= 4.5 * u) & (xs <= cut * sign) & pattern(np.abs(n1), u)
    fold = (s1 > 0) & (n1 >= 1.5 * u) & (n1 <= 4.5 * u) & (xs >= notch * sign) & (xs <= cut * sign)
    return circ | lines_shared | own | fold

def shape(X, Y, p):
    u, c1x, c1y, a, b, cut, notch = p
    c1 = np.array([c1x, c1y])
    c2 = c1 + a * u * D + b * u * NV
    top = half(X, Y, c1, c2, u, cut, notch, +1)
    # The bottom half is the top turned through 180° about the midpoint.
    m = (c1 + c2) / 2
    Xr, Yr = 2 * m[0] - X, 2 * m[1] - Y
    bot = half(Xr, Yr, c1, c2, u, cut, notch, +1)
    return top | bot

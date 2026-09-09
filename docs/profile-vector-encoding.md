# Profile Vector Encoding

## Format

The "Profile Vector" column in the CRM contains a base64-encoded representation of a 1590-dimensional vector of IEEE-754 binary16 (half-precision floating-point) values in little-endian byte order.

Each vector encodes:
- **Dimensions:** 1590 floats
- **Type:** IEEE-754 binary16 (half-precision, 16 bits per value)
- **Memory:** 1590 × 2 bytes = 3,180 bytes
- **Encoded size:** approximately 4,240 base64 characters

This is **not truncation or corruption** — it is the intended encoding format. The ~4,240 character cell size is well under the 50,000-character cell capacity.

## Why Base64 Float16?

Liminer targets Java 11 (`pom.xml` specifies `<release>11</release>`). Native binary16 support (`Float.floatToFloat16` / `Float.float16ToFloat`) was introduced in Java 20 and is not available in Java 11. Therefore:

- Vectors are hand-rolled into little-endian IEEE-754 binary16 using `VectorCodec.encodeBase64(float[])`.
- Subnormal halves (numbers smaller than ~6.1e-5) flush to zero.
- Overflow clamps to half-precision max (≈65504) instead of emitting Infinity.

The base64 encoding allows safe transport through Google Sheets without byte-order issues or escaping.

## Decoding a Cell Value

To turn a stored Profile Vector cell back into a `float[]`:

```java
String cellValue = "...";  // The Profile Vector cell content
float[] vector = VectorCodec.decodeBase64(cellValue);
```

The resulting array has length 1590 and can be used directly with `VectorMath` methods:

```java
// Dot product between two unit-norm vectors (cosine similarity)
double similarity = VectorMath.dot(gpVector, lpVector);

// Other vector operations
double norm = VectorMath.l2Norm(vector);
float[] normalized = VectorMath.normalize(vector);
float[] scaled = VectorMath.scale(vector, factor);
float[] sliced = VectorMath.slice(vector, start, end);
```

## Versioning and Layout Stability

The "Profile Vector Meta" column stores alongside the vector:
- `encoder_version` (e.g., `lp_block_v1`): identifies which layout and encoding algorithm produced this vector.
- Block layout metadata: the exact weights and block assignments used.

This design ensures:
- **Backward compatibility:** Stored vectors remain decodable even after the layout evolves, because the original layout is stored with the vector.
- **Forward protection:** If the encoder version changes, a layout mismatch triggers a re-encode of that row rather than silently using mismatched dot products.

## Current Status

As of this version, no production workflow consumes these vectors yet. They are:
- **Written by:** the `embed-lps` workflow (task 0176), which encodes each LP's canonical profile into a Profile Vector.
- **Consumed by:** unit tests only (`VectorCodec` decode tests, round-trip assertions in `EmbedWorkflowTestMain`).

The GP side of the dot product is built by `GpPreferenceVector`, which is also not yet wired into a scoring workflow. Vector-based LP scoring is ready for integration but awaits a follow-up task.

## Implementation Reference

- `VectorCodec` (`src/main/java/com/liminer/embed/VectorCodec.java`): encodes/decodes with IEEE-754 binary16.
- `VectorMath` (`src/main/java/com/liminer/embed/VectorMath.java`): vector operations (dot, norm, similarity, slice, scale).
- `ProfileVectorLayout` (`src/main/java/com/liminer/embed/ProfileVectorLayout.java`): the 1590-dimensional block structure.
- `ProfileVectorEncoder` (`src/main/java/com/liminer/embed/ProfileVectorEncoder.java`): encodes a `CanonicalProfile` into a vector.
- `GpPreferenceVector` (`src/main/java/com/liminer/embed/GpPreferenceVector.java`): builds the GP side for dot-product comparison.

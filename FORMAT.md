# The Kiff patch format, version 1

A patch turns one file into another, byte for byte. This describes what is in one.

Nothing here is released yet, so version 1 is still moving; the frozen vectors in
`FormatVectorsTest` are what keeps it honest while it does. A format that lives only in code drifts
from its own documentation and the drift is silent — Tinker's bundled bspatch still says bzip2 in
its header comments while the code has always read gzip — so the vectors, not this file, are the
authority. If they disagree, the vectors are right and this file is stale.

All integers are little-endian unless stated. `varint` is LEB128, seven bits per byte, low group
first. `svarint` is the same over a zig-zag encoding, so small negatives stay short.

## Container

```
"KIFF"    4 bytes
version   1 byte      = 1
patcher   1 byte      binary = 1, zip = 2, apk = 3
flags     1 byte
source    varint size, u32 CRC-32 (big-endian)
target    varint size, u32 CRC-32 (big-endian)
payload
```

`flags` is a bit set. One bit is defined:

| bit | meaning |
| --- | --- |
| `0x01` | every region that produces target bytes carries a CRC-32 of them |

**A reader must refuse a flag it does not recognise.** Flags change how the payload is laid out, so
carrying on would produce bytes rather than an error, and bytes that are wrong are worse than a
failure that is loud.

The source checksum is what lets a patch refuse the wrong input file; the target checksum is what
lets it refuse its own output. Both are integrity checks against accident. CRC-32 is not
collision-resistant and none of this is a signature.

## Payload

```
varint  tree size
varint  content size, unpacked
byte    content encoding
varint  content size as stored
bytes   region tree
bytes   content
```

| encoding | code | |
| --- | --- | --- |
| stored | 0 | the bytes as they are, when packing made them bigger |
| LZ77 | 1 | matching only, no entropy coding. Readable, never written |
| LZ77 + Huffman | 2 | matching, then canonical Huffman over the symbols |

Encoding 1 is kept readable because patches written with it exist. It is the same matching as 2
without the entropy coding, and about a sixth of a patch rides on the difference, so there is no
reason to choose it.

The Huffman form writes the code lengths of both alphabets first, four bits each, then the symbol
stream: 256 literals, an end symbol and length codes on one alphabet, distances on another, each
with extra bits. The bucket tables are deflate's; the bit layout is not, and none of it is
deflate-compatible. A reader must refuse an over-subscribed tree rather than decode it into
nonsense.

Every region that carries content appends it to this one stream, consumed in the order the tree is
written. One stream rather than one per region is deliberate: a deep tree would otherwise pay for
its own structure in lost compression, which is more than the structure ever saves.

## Region tree

A node describes a run of target bytes. Nodes nest, and children tile their parent exactly — no
gaps, no overlaps — which is the whole basis of restoring a file byte for byte. A reader must
refuse a tree deeper than 16.

```
node := varint targetLength, byte encoding, [u32 checksum], body
```

The checksum is present when `flags & 0x01` and the encoding is not `COMPOSITE`. A composite
produces nothing of its own; its children cover every byte of it.

| encoding | code | body |
| --- | --- | --- |
| `COMPOSITE` | 0 | `varint childCount`, then that many nodes |
| `DELTA` | 1 | `varint length`, then that many instruction bytes |
| `RAW` | 2 | nothing: `targetLength` bytes are taken from the content stream |
| `TEXT` | 3 | `varint sourceFrom`, `varint sourceLength`, `varint length`, edit bytes |
| `COLUMNS` | 4 | `varint sourceFrom`, `varint sourceLength`, `byte fieldCount`, field widths, then one node |

### DELTA

A sequence of instructions, ending with `END`. The opcode is the low three bits of a varint tag
whose upper bits carry the length: `tag = (length shl 3) or opcode`.

| opcode | code | operands | effect |
| --- | --- | --- | --- |
| `END` | 0 | — | stop |
| `ADD` | 1 | — | take `length` bytes from the content stream |
| `COPY` | 2 | `svarint` | move the source cursor by that much, then copy `length` bytes from there |
| `RUN` | 3 | `byte` | that byte, `length` times |
| `DIFF` | 4 | `svarint` | as `COPY`, but add `length` bytes from the content stream to what is read |

The source cursor starts at 0 for each region and lands after each `COPY` or `DIFF`. Offsets are
into whichever source buffer the enclosing scope provides, which is the file itself except inside a
`COLUMNS` node.

`DIFF` is what keeps a patch small where a build changed offsets but not much else: the differences
are mostly zero and vanish when the content stream is packed, where the same bytes sent through
`ADD` would not compress at all. It also means a difference-encoded region contributes one content
byte per target byte, so it looks expensive before packing and is not.

### TEXT

A line-level edit script, ending with `END`. A line is *bytes up to and including its newline*, and
the last line of a region may have none — which is what makes this exact. Concatenating lines
reproduces the region, so a trailing newline, or its absence, survives.

| opcode | code | operand | effect |
| --- | --- | --- | --- |
| `END` | 0 | — | stop |
| `EQUAL` | 1 | `varint` | copy that many source lines |
| `DELETE` | 2 | `varint` | skip that many source lines |
| `INSERT` | 3 | `varint` | take that many bytes from the content stream |

Source lines come from `source[sourceFrom, sourceFrom + sourceLength)`.

### COLUMNS

For a region that is a table of fixed-width rows. Both sides are rearranged the same way, the node
inside describes the target's rearranged bytes against the source's, and the result is rearranged
back.

The rearrangement reads the table column by column rather than row by row, storing each column as
differences from the entry before it, in the field's own width and wrapping within it. A trailing
partial row is copied across untouched.

The node inside carries no checksum: it works on rearranged bytes, which are not target bytes, and
the node around it covers what the rearrangement finally produces.

This exists because a table of ids is the renumbering problem in its purest form — every entry of a
dex's `string_ids` is an offset into the file, so inserting one string shifts all of them — while
the gaps between those entries barely move.

## Applying without holding the files

A patch describes its target in order, front to back, and addresses its source at arbitrary
offsets. So a reader needs random access to the source and only somewhere to put the target - never
either file in memory.

Two encodings are the exceptions, and both are bounded by one region rather than by the file:
`TEXT` needs its source range in memory to split into lines, and `COLUMNS` needs its source range
rearranged and its own output built before either can be used. The checksums make this workable in
the first place: a streamed restore cannot be checked after the fact, because afterwards the bytes
are gone.

## What a reader must refuse

Not a list of nice-to-haves. Each of these is a way for a malformed patch to produce bytes instead
of an error.

- a magic that is not `KIFF`, or a version it does not implement
- a flag bit it does not recognise
- a varint wider than 64 bits, or a negative value where a size, count or length belongs
- a tree nested deeper than 16
- a region that runs past the end of the target, or a composite whose children do not add up to it
- an instruction reading outside the source, or past the end of the content stream
- a `COLUMNS` field layout it cannot apply, or one naming a source range outside the source
- content longer than the target, which no patch can use and which names its own allocation
- a region whose checksum does not match what it produced
- a restored target whose checksum does not match the header

One bound a reader cannot derive: the target size is declared in the header, and a reader returning
the result as an array has to allocate that much before anything has corroborated it. Restoring
into a `RestoreTarget` does not, which is the safer shape for a patch from somewhere you do not
control; a caller who knows what it is expecting can also check `Kiff.info(patch).targetSize` first.

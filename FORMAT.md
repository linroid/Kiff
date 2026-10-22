# The Kiff patch format, version 1

A patch turns one file into another, byte for byte. This describes what is in one.

Version 1 is final as of Kiff 1.0.0. Kiff 0.1.0 and 0.2.0, released before it, wrote version-1
patches too, and every later release still reads them; [Versions and flags](#versions-and-flags)
says what changed in version 1 since, and what is left room for.

This document is the specification: a reader built from it alone applies every version-1 patch.
The frozen vectors in `FormatVectorsTest` are its conformance tests. A format that lives only in
code drifts from its own documentation and the drift is silent — Tinker's bundled bspatch still
says bzip2 in its header comments while the code has always read gzip — which is why the vectors
exist. Where one disagrees with this document, one of them has a bug.

`varint` is LEB128: seven bits per byte, low group first, at most 64 bits. `svarint` is the same
over a zig-zag encoding, so small negatives stay short. The only fixed-width integers are CRC-32s,
and they are big-endian. The CRC is the one zip and zlib use (CRC-32/ISO-HDLC: reflected
polynomial `0xEDB88320`, initial value and final XOR `0xFFFFFFFF`). Values inside a `COLUMNS`
table are data rather than framing, and are read little-endian, as [COLUMNS](#columns) describes.

## Container

```
"KIFF"    4 bytes
version   1 byte      = 1
patcher   1 byte      binary = 1, zip = 2, apk = 3: what the writer took the target to be
flags     1 byte
source    varint size, u32 CRC-32 (big-endian)
target    varint size, u32 CRC-32 (big-endian)
payload
```

`patcher` is informational: what the writer took the target to be as a whole, for display and
diagnostics. Nothing in the payload depends on it, and every patch is applied the same way whatever
it says. The tree records the structure a patch was built with, so a reader never needs this to
apply one.

Kiff 0.1.0 had one patcher per code, chosen by its caller, and each of them refuses a patch that
carries another's code. A writer therefore records the code of the patcher that suits the target,
which is the one a 0.1.0 caller applies it with: `apk` for a zip that carries an
`AndroidManifest.xml`, `zip` for any other zip whose directory reads, and `binary` for everything
else, including a file the writer took apart with a container format of its own. Kiff 0.1.0 refuses
any other value too, so a version-1 patch carries one of these three, and a reader refuses the
rest.

`flags` is a bit set. Two bits are defined:

| bit | meaning |
| --- | --- |
| `0x01` | every region that produces target bytes carries a CRC-32 of them |
| `0x02` | the region tree is packed with content encoding 2: see [Payload](#payload) |

The other six bits are reserved and must be zero. **A reader must refuse a flag it does not
recognise.** Flags change how the payload is laid out, so carrying on would produce bytes rather
than an error, and bytes that are wrong are worse than a failure that is loud.

The source checksum is what lets a patch refuse the wrong input file; the target checksum is what
lets it refuse its own output. Both are integrity checks against accident. CRC-32 is not
collision-resistant and none of this is a signature.

A patch file is conventionally named with a `.kiff` extension. A reader identifies one by its
magic, never by its name, and the name carries no version: the header does.

## Versions and flags

The version byte says which reader can apply a patch, and it is the only thing that does. A reader
of version N applies every version-N patch exactly as this document describes, and refuses any
version it does not implement.

Once a version is final, the set of patches valid under it is fixed. Anything a reader of that
version would refuse, or would read differently, takes a new version: a new flag bit, region
encoding, content encoding, delta or text opcode, patcher code, a new or moved field, or a looser
limit such as a deeper tree or a longer length. A change in how a writer chooses among what the
version already allows needs no new version, and neither does a clearer description of the same
bytes. The magic and the version byte keep their place in every version, so any reader can tell
which version a patch needs.

Flags are options a writer picks for one patch, among those every reader of its version
understands. From 1.0.0 on, new flags are defined only together with a new version.

Version 1 is final as of Kiff 1.0.0, and it grew once before that. Kiff 0.1.0 and 0.2.0 came out
before the format was final, and wrote version-1 patches with flags `0x01` or none. Flag `0x02`,
the packed tree, was defined in version 1 after them, and nothing else changed: every patch those
two wrote means what it always did, and every later release reads it. A reader must refuse a flag
it does not recognise, so 0.1.0 and 0.2.0 refuse a patch that carries `0x02`, from its header
alone, before reading anything of its payload. A writer sets `0x02` only where it makes a patch
smaller, so a patch that gains nothing from it is exactly the patch those releases wrote, and
remains one they read.

A writer writes the lowest version that can express the patch, so a patch that uses nothing new
stays readable by older readers. That matters most where a patch updates the software that applies
it: the reader is the one in the release being replaced.

## Payload

```
varint  tree size, unpacked
varint  tree size as stored     only when flags & 0x02
varint  content size, unpacked
byte    content encoding
varint  content size as stored
bytes   region tree, as stored
bytes   content, as stored
```

Without flag `0x02`, the tree is stored as it is, and its stored size is its size, so the second
field is not there. With it, the stored tree is one LZ77 + Huffman stream of its own, exactly as
[content encoding 2](#the-lz77--huffman-stream) describes, which unpacks to the tree size; the tree
is then read from the unpacked bytes as it would be from stored ones, and is held to every rule a
stored tree is. The flag is the tree's encoding, so there is no encoding byte for it, and the tree
is packed apart from the content rather than together with it: the two read nothing alike, and one
Huffman tree for both would fit neither.

A tree is small beside the content for most patches. It is not for text, whose line scripts and
instructions can be most of a patch: on a pair of 110 MiB R8 mapping files, 3.4 MiB of a 4.8 MiB
patch. A writer packs the tree only where that makes the patch strictly smaller, counting the
second size field, and only where the tree is no longer than the target.

| encoding | code | |
| --- | --- | --- |
| stored | 0 | the bytes as they are, when packing made them bigger |
| LZ77 + Huffman | 2 | matching, then canonical Huffman over the symbols |

Code 1 is not part of version 1. Builds before the first release used it for the same matching
without the entropy coding; no released Kiff wrote it, and a reader refuses it like any other
encoding it does not know. Its stored size bounded nothing it could unpack to, which made it the one
way for a few bytes of patch to name an allocation as large as the target.

The Huffman form writes the code lengths of both alphabets first, four bits each, then the symbol
stream: 256 literals, an end symbol and length codes on one alphabet, distances on another, each
with extra bits. The distance buckets extend deflate's to a 1 MiB window and the length buckets are
Kiff's own, so none of it is deflate-compatible. A reader must refuse an over-subscribed tree rather
than decode it into nonsense.

Every region that carries content appends it to this one stream, consumed in the order the tree is
written. One stream rather than one per region is deliberate: a deep tree would otherwise pay for
its own structure in lost compression, which is more than the structure ever saves.

## Region tree

A node describes a run of target bytes. Nodes nest, and children tile their parent exactly — no
gaps, no overlaps — which is the whole basis of restoring a file byte for byte. The root is at
depth 0, and a composite's children and a `COLUMNS` node's inner node are each one deeper. A reader
must refuse a tree deeper than 16, so a leaf may sit at depth 16 and no further.

```
node := varint targetLength, byte encoding, [u32 checksum, big-endian], body
```

The checksum is present when `flags & 0x01` and the encoding is not `COMPOSITE`, except inside a
`COLUMNS` node, where no node carries one at any depth. A composite produces nothing of its own; its
children cover every byte of it.

| encoding | code | body |
| --- | --- | --- |
| `COMPOSITE` | 0 | `varint childCount`, then that many nodes |
| `DELTA` | 1 | `varint length`, then that many instruction bytes |
| `RAW` | 2 | nothing: `targetLength` bytes are taken from the content stream |
| `TEXT` | 3 | `varint sourceFrom`, `varint sourceLength`, `varint length`, edit bytes |
| `COLUMNS` | 4 | `varint sourceFrom`, `varint sourceLength`, `byte fieldCount`, one width byte per field, then one node with no `COLUMNS` in it |

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
reproduces the region, so a trailing newline, or its absence, survives. A newline is the byte
`0x0A`; a `0x0D` before it simply belongs to the line.

Each edit is one opcode byte, followed by a varint for all but `END`.

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
partial row is copied across untouched. Exactly, for a range of `length` bytes and fields of
`w1 … wn` bytes:

- a row is `w1 + … + wn` bytes, and there are `length / row` whole rows
- each field's value is an unsigned little-endian integer of its width
- the output is field 1 of every row, then field 2 of every row, and so on; each value is written
  as its difference from the same field in the row before, the first row's from 0, modulo
  `2^(8 × width)`, little-endian in the field's width
- the `length mod row` bytes after the last whole row follow unchanged

Rearranging back undoes each step. The target region is rearranged with its own length, and the
source range named by `sourceFrom` and `sourceLength` with its own, which may differ.

A layout has at least one field, each 1 to 8 bytes wide, a row of at most 255 bytes, and at least
8 whole rows. Those are the limits Kiff 0.1.0 reads, so every version-1 patch keeps to them.

Nothing inside a `COLUMNS` node carries a checksum, at any depth: it works on rearranged bytes,
which are not target bytes, and the node around it covers what the rearrangement finally produces.
It may not hold another `COLUMNS` node at any depth: each one builds its output whole before
emitting it, so nesting them would hold a region's worth again for every level.

This exists because a table of ids is the renumbering problem in its purest form — every entry of a
dex's `string_ids` is an offset into the file, so inserting one string shifts all of them — while
the gaps between those entries barely move.

## The LZ77 + Huffman stream

Content encoding 2 packs the whole content stream as one block, and flag `0x02` packs the region
tree the same way, as a block of its own. Bits are read most significant first within each byte,
extra bits included, and the last byte is padded with zero bits.

1. 289 code lengths for the literal and length alphabet, then 40 for the distance alphabet, each
   four bits, so 1,316 bits in all. A length is 0 to 15, and 0 means the symbol is not used.
2. Within each alphabet, codes are canonical, as in deflate: symbols get consecutive codes in order
   of code length, then of symbol. A set of lengths that over-subscribes the code space is refused.
   One that leaves codes unassigned is allowed, and reading an unassigned code is refused.
3. Then symbols from the literal and length alphabet, until the end:
   - `0` to `255` is that byte;
   - `256` is the end, and by then the stream has produced exactly the size declared for it, the
     content's or the tree's;
   - `257 + i` is a match whose length is `lengthBase[i]` plus `lengthExtra[i]` extra bits. A
     distance symbol `j` follows, for a distance of `distanceBase[j]` plus `distanceExtra[j]` extra
     bits. The distance may not reach back past the start, and the match copies one byte at a time,
     so it may overlap what it is producing.

| length symbols `i` | `lengthBase` | `lengthExtra` |
| --- | --- | --- |
| 0 – 7 | 4, 5, 6, 7, 8, 9, 10, 11 | 0 |
| 8 – 11 | 12, 14, 16, 18 | 1 |
| 12 – 14 | 20, 24, 28 | 2 |
| 15 – 17 | 32, 40, 48 | 3 |
| 18 – 19 | 56, 72 | 4 |
| 20 – 21 | 88, 120 | 5 |
| 22 – 23 | 152, 216 | 6 |
| 24 – 25 | 280, 408 | 7 |
| 26 – 27 | 536, 792 | 8 |
| 28 – 29 | 1048, 1560 | 9 |
| 30 – 31 | 2072, 3096 | 10 |

So a match is 4 to 4,119 bytes long. The distance symbols are deflate's thirty, extended by ten:
`distanceBase` is 1, 2, 3 and 4 for `j` up to 3, then `((2 + j mod 2) << (j / 2 - 1)) + 1`, and
`distanceExtra` is 0 for `j` up to 3, then `j / 2 - 1`. The longest distance, at `j = 39`, is
786,433 plus 18 extra bits: 1,048,576 bytes, a window of 1 MiB.

## Applying without holding the files

A patch describes its target in order, front to back, and addresses its source at arbitrary
offsets. So a reader needs random access to the source and only somewhere to put the target - never
either file in memory.

Two encodings are the exceptions, and both are bounded by one region rather than by the file:
`TEXT` needs its source range in memory, finding each line by walking forward to it rather than by
indexing them all, and `COLUMNS` needs its source range rearranged and its own output built before
either can be used. The checksums make this workable in the first place: a streamed restore cannot
be checked after the fact, because afterwards the bytes are gone.

The content stream is the other thing a reader holds. Kiff's reader unpacks it whole before the
first region, so a patch whose target is mostly new content costs about its target in memory, since
that new content is what the stream carries. Regions consume the stream strictly in order, so a
reader could unpack it as it goes instead, holding only the last 1 MiB, which is as far back as a
match reaches; nothing in the format stands in the way.

A packed tree is the third. Kiff's reader unpacks it whole before the first region too, and holds
it beside the patch until the restore is done, so it adds the unpacked tree size to what a restore
needs: never more than the target, which is as long as a reader lets a packed tree be, and in
practice a few percent of it. Between two 110 MiB R8 mapping files the unpacked tree is 3.4 MiB,
and a restore's peak memory rose by 4 MiB; between two 72 MiB release APKs it is 224 KiB, which did
not show. The tree is read strictly in order as well, so it could be unpacked as it goes, in the
same 1 MiB window.

## What a reader must refuse

Not a list of nice-to-haves. Each of these is a way for a malformed patch to produce bytes instead
of an error, or to carry bytes that nothing accounts for, which is where corruption hides.

- a magic that is not `KIFF`, or a version it does not implement
- a flag bit it does not recognise
- a patcher code, region encoding, content encoding, delta opcode or text opcode it does not know
- a varint wider than 64 bits, or a negative value where a size, count or length belongs
- a region, a source range, the tree or the content longer than 2,147,483,647 bytes, unpacked or
  as stored: a larger target is a composite of regions that each fit
- a tree nested deeper than 16
- a region that runs past the end of the target, a composite whose children do not add up to it, or
  a tree whose root does not cover the declared target, refused before anything is written
- an instruction reading outside the source, or past the end of the content stream
- a `COLUMNS` layout with no fields, a field narrower than 1 byte or wider than 8, a row wider than
  255 bytes, or fewer than 8 whole rows, or one naming a source range outside the source
- a `COLUMNS` node inside another, directly or through a composite
- a block that ends anywhere but its declared length: the tree, a `DELTA` region's instructions, or
  a `TEXT` region's edits
- content longer than the target, which no patch can use and which names its own allocation
- a packed tree longer than the target once unpacked: a stored tree may be, since a line script that
  deletes can run longer than what it keeps, but a writer stores such a tree rather than pack it, so
  that what a reader unpacks never exceeds the target it was told to expect
- content, or a packed tree, longer than its stored bytes can unpack to: exactly their own length
  when stored, and for LZ77 + Huffman at most 4,119 bytes - the longest match - for every twelve
  bits after the code lengths and the end symbol, which is what that match's length symbol, ten
  extra bits and distance symbol take at the least
- content the tree does not use up, or any byte after the stored content
- an LZ77 + Huffman stream, of content or of a tree, that over-subscribes its code space, reads a
  code no length assigns, ends mid-symbol, unpacks to anything but its declared size, or has
  anything after its end symbol, a set bit in the padding of its last byte included, or any stored
  byte at all for nothing
- a region whose checksum does not match what it produced
- a restored target whose checksum does not match the header

Kiff 0.1.0, the first release, still read code 1 and let trailing and unused bytes pass. No
released Kiff writes either, so these rules refuse no patch Kiff has written; 0.1.0 is only more
lenient than the format. Kiff 0.1.0 and 0.2.0 also refuse flag `0x02`, which is newer than them.

One bound a reader cannot derive: the target size is declared in the header, and nothing
corroborates it until the restore is done. A reader returning the result as an array allocates that
much up front; restoring into a `RestoreTarget` does not, but the unpacked content, a packed tree
and a `COLUMNS` region can each be as large as the target, and a patch can still name them. What
this reader refuses keeps every allocation within the declared target size, or the source's own size
for the range a `TEXT` or `COLUMNS` region reads. So for a patch from somewhere you do not control,
check `Kiff.info(patch).targetSize` against what you are expecting before applying it.

Those bounds hold region by region. Each `TEXT` or `COLUMNS` region reads its source range afresh,
and nothing limits how many regions name the same range, so the reading a restore does is bounded
by the patch's size times the source's rather than by either alone.

## What a reader must accept

Some patches say a thing in more bytes than they need. Each of these still means exactly one
thing and leaves no byte unaccounted for, so it is a valid patch, and a reader that refused it
would be refusing what a writer is entitled to write.

- a varint longer than it needs to be
- a `DELTA` instruction or a `TEXT` edit of length zero
- a composite with no children, and a region of no bytes
- a `TEXT` script that stops before the end of its source range: the rest is not copied
- flags `0`, so a patch with no region checksums at all
- a packed tree that is no smaller than it would be stored, or a tree stored that packing would
  have made smaller

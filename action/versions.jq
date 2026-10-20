# Puts release tags in the order their versions run, for finding the releases before another.
#
# jq sorts without a comparator, so each tag becomes a key that jq's own ordering of arrays,
# numbers and strings compares the right way.

def _without_v: if test("^[vV][0-9]") then .[1:] else . end;

# The key of a tag that reads as a version, or null for one that does not.
#
# A version is numbers joined by dots after an optional leading v, then an optional -prerelease and
# +build, and is ordered by Semantic Versioning's precedence: 1.4.0-rc.1 < 1.4.0 < 1.10.0. Missing
# numbers count as zeros, so v2 is 2.0.0. The build is not part of the order.
def version_key:
  _without_v
  | first(
      capture("^(?<core>[0-9]+(?:\\.[0-9]+)*)(?:-(?<pre>[0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    ) // null
  | if . == null then null else
      (.core | split(".") | map(tonumber)) as $core
      | [
          $core + [0, 0, 0][($core | length):],
          # A release follows its prereleases. Their identifiers compare as SemVer has it, because
          # jq puts numbers before strings and a shorter array before one it starts.
          if .pre == null then [1]
          else [0, (.pre | split(".") | map(if test("^[0-9]+$") then tonumber else . end))]
          end
        ]
    end;

# The key of any tag, read naturally: runs of digits as numbers, so build-9 < build-10.
def natural_key:
  _without_v | [scan("[0-9]+|[^0-9]+") | if test("^[0-9]") then tonumber else . end];

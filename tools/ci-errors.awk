# Turn a Gradle log into GitHub Actions annotations, one per source file.
#
# Why awk and not the step log: CI log bodies are served from a blob store the API cannot read here,
# but annotations are retrievable — and GitHub caps them (~50 per level), so a 104-error build must not
# emit one annotation per error. This groups per file, keeps the first 12 distinct messages of each file,
# drops the exact repeats, and prints the total so a truncated tail is still visible in the numbers.
#
# usage: awk -f tools/ci-errors.awk /tmp/dbg.log
#
# Kotlin 1.9 emits `e: /abs/path/File.kt:LINE:COL: message` (no "error:" word), so that is the shape parsed.
function esc(s) { gsub(/%/, "%25", s); gsub(/\r/, "%0D", s); gsub(/\n/, "%0A", s); return s }
{
    if ($0 ~ /^e: .*\.kt:[0-9]+:[0-9]+/) {
        s = substr($0, 4)
        sub(/^file:\/\//, "", s)                         # Kotlin prints e: file:///abs/path
        sub(/^\/home\/runner\/work\/[^/]+\/[^/]+\//, "", s)  # -> repo-relative, greppable
        p = s; sub(/:[0-9]+:[0-9]+.*$/, "", p)      # file path
        m = s; sub(/^[^:]*:[0-9]+:[0-9]+: ?/, "", m) # the message itself
        ln = s; sub(/^[^:]*:/, "", ln); sub(/:.*$/, "", ln)
        f = p; sub(/.*\//, "", f)                    # File.kt
        key = f "|" ln "|" m
        if (seen[key]++) { dup[f]++; next }
        n[f]++
        if (!(f in line1)) line1[f] = ln
        line2[f] = ln
        if (n[f] <= 12) text[f] = text[f] (n[f] > 1 ? "%0A" : "") ln ": " esc(m)
        path[f] = p
        total++
        next
    }
    # A build can also die before compilation (aapt2, gradle script, missing dependency).
    if ($0 ~ /^FAILURE:/ || $0 ~ /^> Task .*FAILED/ || $0 ~ /What went wrong/ ||
        ($0 ~ /error:/ && $0 ~ /\.xml/)) {
        grad = grad esc($0) "%0A"
        if (gradn++ < 6) print "::error title=build step::" esc($0) > "/dev/stderr"
    }
}
END {
    for (f in n) {
        d = (f in dup ? " (+" dup[f] " repeated)" : "")
        print "::error title=" f " [" n[f] "]" d "::" esc(path[f]) " lines " line1[f] "-" line2[f] ": " text[f]
    }
    if (grad != "") print "::error title=gradle failure::" grad
    print "kotlin error lines: " total+0
}

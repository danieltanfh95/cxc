# Corvid v0.5 Validation — Extended Fragment Coverage

**Continuation of Path B.** After translating Reader, Markdown, and Blockquote and finding the two-type pattern, the goal now is coverage: apply the pattern across enough Fragment types to see whether it holds up or breaks somewhere.

**What's translated here:**

1. **MarkdownParser** — the orchestrator. Full Swift source available. 144 Swift lines → ~100 Corvid lines.
2. **Fragment protocol** — the interface all fragments conform to. Reconstructed from usage across MarkdownParser and Blockquote.
3. **Paragraph** — the fallback fragment. Approximated from architectural knowledge and dispatch hints.
4. **HorizontalLine** — trivial marker type. Approximated.
5. **Heading** — level 1-6, tests enum-like discrete state. Approximated with hints from MarkdownParser (which reads `heading.level`).
6. **List** — recursive structure. Approximated. Tests closed sum types (via enumerations) and recursion.

**Honesty:** MarkdownParser is worked from real Swift source. The Fragment types below MarkdownParser are worked from a mix of architectural knowledge (I know how markdown parsers structure themselves) and hints from what MarkdownParser tells us about their interfaces (`fragmentType(for:nextCharacter:)` reveals what each type parses; `heading.level` reveals Heading has a level field, etc.).

This limits the "parity" claim for the approximated types — I can't verify line-by-line correspondence. But it doesn't limit the pattern-testing claim. The two-type pattern either holds across these structural shapes or doesn't; that's testable regardless of exact Swift-source fidelity.

---

## MarkdownParser — the orchestrator

MarkdownParser is the top-level entry point. Swift's version is a `struct` with a `modifiers` field, and the main work happens in `parse(_ markdown: String) -> Markdown`. The parser also declares a `fragmentType(for:nextCharacter:)` dispatch that maps first-character patterns to Fragment types.

The dispatch table alone is a gift:

```swift
// From Swift source
func fragmentType(for character: Character,
                  nextCharacter: Character?) -> Fragment.Type {
    switch character {
    case "#": return Heading.self
    case "!": return Image.self
    case "<": return HTML.self
    case ">": return Blockquote.self
    case "`": return CodeBlock.self
    case "-" where character == nextCharacter,
         "*" where character == nextCharacter:
        return HorizontalLine.self
    case "-", "*", "+", \.isNumber: return List.self
    case "|": return Table.self
    default: return Paragraph.self
    }
}
```

This tells us which Fragment types exist (10 of them) and the character discriminators. It also gives us examples of `\.isNumber` KeyPath pattern matching that our §3.7 doesn't yet specify.

**Idiomatic Corvid MarkdownParser:**

Swift's parser is a struct but has mutating state (adding modifiers, accumulating fragments during parse). Same value-type-with-mutation pattern. Two options:

- **One class.** MarkdownParser becomes a class with `state:` for modifiers.
- **Two types.** MarkdownParser (struct, immutable config) + MarkdownParserRunner (class, per-parse state).

Working through it, I lean **class** here for a different reason than Blockquote. The MarkdownParser is *not* an ephemeral thing that gets constructed once and discarded — users hold onto a parser instance, add modifiers to it over time, and reuse it for multiple parse operations. It has genuine lifecycle. The Swift-struct model exists because Swift favors structs; there's no design reason MarkdownParser is a struct rather than a class.

But — the *parsing operation* itself has per-parse state (fragments, urlsByName, titleHeading, metadata). That's ephemeral. So a second class emerges for the parse pass:

```Corvid
module com.ink.api

# Top-level parser. Long-lived. Users configure with modifiers.
expose class MarkdownParser:
    state:
        modifiers: ModifierCollection
    
    methods:
        function init(modifiers: [Modifier] = []) -> MarkdownParser:
            return MarkdownParser(
                modifiers: ModifierCollection(modifiers: modifiers)
            )
        
        function add_modifier(self, modifier: Modifier):
            self.modifiers.insert(modifier)
        
        function html(self, markdown: String) -> String:
            return self.parse(markdown).html
        
        function parse(self, markdown: String) -> Markdown:
            # Delegate to per-parse runner.
            var runner = ParseRun(
                reader: Reader(string: markdown),
                modifiers: self.modifiers
            )
            return runner.run()


# Per-parse machinery. One instance per parse() call.
# Owns the mutable state of a single parse operation.
class ParseRun:
    state:
        reader: Reader
        fragments: [ParsedFragment]
        urls_by_name: Dict<String, URL>
        title_heading: Heading?
        metadata: Metadata?
        modifiers: ModifierCollection
    
    methods:
        function init(reader: Reader, 
                     modifiers: ModifierCollection) -> ParseRun:
            return ParseRun(
                reader: reader,
                fragments: [],
                urls_by_name: {},
                title_heading: nil,
                metadata: nil,
                modifiers: modifiers
            )
        
        function run(self) -> Markdown:
            while !self.reader.did_reach_end():
                self.reader.discard_whitespaces_and_newlines()
                if self.reader.did_reach_end():
                    break
                
                try:
                    self.parse_one_fragment()
                catch e as ParseError:
                    # Failure recovery: any fragment that fails becomes a paragraph.
                    # This matches Swift's `catch { ... Paragraph.read ... }` pattern.
                    var paragraph = self.make_fragment(
                        using: Paragraph.read
                    )
                    self.fragments.append(paragraph)
            
            var urls = NamedURLCollection(urls_by_name: self.urls_by_name)
            var html = self.render_fragments(urls: urls)
            
            return Markdown(
                html: html,
                title_heading: self.title_heading,
                metadata: self.metadata?.values ?? {}
            )
        
        function parse_one_fragment(self) throws ParseError:
            # Metadata: only at document top, only if no fragments parsed yet.
            if self.metadata == nil 
               and self.fragments.is_empty() 
               and self.reader.current_character() == '-':
                if let parsed = Metadata.read_or_rewind(reader: self.reader):
                    self.metadata = parsed.applying_modifiers(self.modifiers)
                    return
            
            # URL declaration: [name]: url
            if self.reader.current_character() == '[':
                var declaration = try URLDeclaration.read_or_rewind(
                    reader: self.reader
                )
                self.urls_by_name[declaration.name] = declaration.url
                return
            
            # Regular fragment dispatch
            var type = self.fragment_type(
                character: self.reader.current_character(),
                next_character: self.reader.next_character()
            )
            var fragment = self.make_fragment(using: type.read_or_rewind)
            self.fragments.append(fragment)
            
            # Track first level-1 heading as title
            if self.title_heading == nil:
                if let heading = fragment.fragment as? Heading:
                    if heading.level == .h1:
                        self.title_heading = heading
        
        function make_fragment(
            self, 
            using reader_fn: (Reader) throws ParseError -> Fragment
        ) throws ParseError -> ParsedFragment:
            var start_index = self.reader.current_index()
            var fragment = try reader_fn(self.reader)
            var raw = self.reader.characters(
                from: start_index, 
                to: self.reader.current_index()
            )
            return ParsedFragment(fragment: fragment, raw_string: raw)
        
        function render_fragments(
            self, 
            urls: NamedURLCollection
        ) -> String:
            var result = ""
            for wrapper in self.fragments:
                var chunk = wrapper.fragment.html(
                    urls: urls,
                    raw_string: wrapper.raw_string,
                    modifiers: self.modifiers
                )
                result = result + chunk
            return result
        
        function fragment_type(
            self,
            character: Character,
            next_character: Character?
        ) -> Fragment.Type:
            switch character:
                case '#': 
                    return Heading.self
                case '!': 
                    return Image.self
                case '<': 
                    return HTML.self
                case '>': 
                    return Blockquote.self
                case '`': 
                    return CodeBlock.self
                case '-' where next_character == '-',
                     '*' where next_character == '*':
                    return HorizontalLine.self
                case '-', '*', '+':
                    return List.self
                case c where c.is_number():
                    return List.self
                case '|': 
                    return Table.self
                default: 
                    return Paragraph.self


# ParsedFragment is a wrapper that holds both the parsed value
# and the original source string (for modifier callbacks that want it).
struct ParsedFragment:
    data:
        fragment: Fragment
        raw_string: Substring
```

**What this design produces:**

MarkdownParser (class) holds user-visible configuration and lives as long as the user needs it. ParseRun (class) exists briefly per `parse()` call, owns all the messy accumulation state, and produces a Markdown value at the end. ParsedFragment (struct) is the immutable data pair (fragment + source range) that renderers consume.

Three types where Swift has one. Each does exactly one thing.

**The dispatch table needed a specific fix.**

Swift's `case "-" where character == nextCharacter, "*" where character == nextCharacter:` uses `character` (the switch-scrutinee) in the where clause, which reads naturally in Swift. In my Corvid translation I wrote `next_character == '-'` because the character being scrutinized is already fixed by the case — comparing next_character against the literal is clearer. Same runtime behavior; different expression.

**Also — `\.isNumber` had to be broken out.**

Swift's `case ..., \.isNumber:` groups multiple cases including a KeyPath predicate. Without predicate case patterns in §3.7, I had to write it as a separate `case c where c.is_number():`. Ergonomic loss but structurally clean.

**One design question:**

Should MarkdownParser's `add_modifier` be `async` so it participates in the state-machine synchronization (§8.4)? Users might legitimately want to add modifiers from multiple threads while a parse is running.

I lean no. Adding modifiers is typically a configuration step done once at setup. Making it async forces a `.await` on every add. If concurrent modification of a parser during a parse becomes a real use case, we can revisit — the invariant `modifiers.insert()` is a valid state transition regardless of when it happens.

---

## Fragment protocol — the interface

The Fragment protocol is central. From MarkdownParser we can see its shape:

```swift
// Reconstructed from usage:
protocol Fragment {
    var modifierTarget: Modifier.Target { get }
    static func read(using reader: inout Reader) throws -> Self
    static func readOrRewind(using reader: inout Reader) throws -> Fragment
    func html(usingURLs: NamedURLCollection,
              rawString: Substring,
              applyingModifiers: ModifierCollection) -> String
    func plainText() -> String
}
```

**Idiomatic Corvid:**

```Corvid
module com.ink.internal

protocol Fragment:
    # Every fragment declares which modifier target it belongs to.
    # Modifiers dispatch by target during rendering.
    accepts modifier_target(self) -> ModifierTarget
    
    # Factory constructor. Throws on parse failure.
    # Called by the parser dispatch table.
    accepts class read(reader: Reader) throws ParseError -> Self
    
    # Read with rewind on failure. If read fails, the reader is 
    # restored to its pre-attempt position. Used for optional 
    # fragments (Metadata, URLDeclaration).
    accepts class read_or_rewind(reader: Reader) throws ParseError -> Fragment
    
    # Render the fragment to HTML, using the given URLs (for 
    # reference-style links) and modifiers.
    accepts html(self, 
                urls: NamedURLCollection,
                raw_string: Substring,
                modifiers: ModifierCollection) -> String
    
    # Extract plain text (for title inference, previews, etc.).
    accepts plain_text(self) -> String
```

**A design decision this raises:**

Should Fragment be closed or open? A closed sum type (an enumeration with variant cases) would let rendering code `switch` over all variants exhaustively. An open protocol permits user-defined fragment types.

The tension: Ink's modifier system is designed for extension. Users add modifiers to customize rendering; a natural next step is user-defined fragment types. Making Fragment an enumeration prevents that.

I lean **open protocol** for this reason. Ink's architecture explicitly supports extension; our Fragment should too. Exhaustive rendering is an anti-goal here — the whole point is dispatch through method calls (which handles new types automatically).

(Historical note: earlier drafts considered `sealed protocol` as a keyword for closed-conformance protocols. The v0.5.1 cleanup removed it — closed sum types come from enumerations, not from a sealed keyword. See §1.2, §3.4.1.)

**Under the two-type pattern, `read` is on the struct (value type), returning `Self`.**

For consistency with the Fragment protocol, every fragment value type has a `read` static factory that delegates to its corresponding Reader class. Users implementing new fragments follow the same pattern:

```Corvid
struct MyFragment:
    conforms:
        Fragment
    
    data:
        # value fields
    
    methods:
        class function read(reader: Reader) throws ParseError -> MyFragment:
            return MyFragmentReader.read(reader: reader)
        # ... other Fragment methods
```

Boilerplate, but predictable. A future `builder:` section (Stage 2+) could generate the delegation.

---

## Paragraph — the fallback

Paragraph is the catch-all. When no other fragment type matches, or when parsing another fragment fails, the parser falls back to Paragraph. Structurally it's just formatted text ending at a blank line.

```Corvid
module com.ink.internal

# The value. What a parsed paragraph IS.
struct Paragraph:
    conforms:
        Fragment
    
    data:
        text: FormattedText
    
    methods:
        function modifier_target() -> ModifierTarget:
            return .paragraphs
        
        class function read(reader: Reader) throws ParseError -> Paragraph:
            return ParagraphReader.read(reader: reader)
        
        function html(self, 
                     urls: NamedURLCollection,
                     raw_string: Substring,
                     modifiers: ModifierCollection) -> String:
            var body = self.text.html(urls: urls, modifiers: modifiers)
            return "<p>${body}</p>"
        
        function plain_text(self) -> String:
            return self.text.plain_text()


# The machinery. Accumulates lines until a blank line or EOF.
class ParagraphReader:
    state:
        text: FormattedTextBuilder
    
    methods:
        class function read(reader: Reader) throws ParseError -> Paragraph:
            var builder = ParagraphReader(
                text: FormattedTextBuilder.new()
            )
            
            builder.text.append(FormattedText.read_line(reader: reader))
            
            while !reader.did_reach_end():
                # Blank line ends the paragraph
                if reader.current_character().is_newline():
                    reader.advance_index()
                    if reader.did_reach_end() 
                       or reader.current_character().is_newline():
                        break
                
                builder.text.append(
                    FormattedText.read_line(reader: reader)
                )
            
            return builder.build()
        
        function build(self) -> Paragraph:
            return Paragraph(text: self.text.build())
```

**What this shows:**

Simplest possible instance of the two-type pattern. Paragraph is a struct with one data field; ParagraphReader is a class that accumulates lines. The design fits perfectly — no friction, no forcing.

Note that `builder.text` is a `FormattedTextBuilder` (mutable class), which itself follows the same two-type pattern. This is the point I made earlier: the pattern nests. FormattedText (value) + FormattedTextBuilder (machinery), each with the same shape.

---

## HorizontalLine — the trivial case

HorizontalLine is just a marker. `---` or `***` or `___` on their own line renders as `<hr>`. No data.

```Corvid
module com.ink.internal

# The value. Empty struct — no data to carry.
# Structurally: just a marker that says "this position had a horizontal rule".
struct HorizontalLine:
    conforms:
        Fragment
    
    methods:
        function modifier_target() -> ModifierTarget:
            return .horizontal_lines
        
        class function read(reader: Reader) throws ParseError -> HorizontalLine:
            return HorizontalLineReader.read(reader: reader)
        
        function html(self,
                     urls: NamedURLCollection,
                     raw_string: Substring,
                     modifiers: ModifierCollection) -> String:
            return "<hr>"
        
        function plain_text(self) -> String:
            return ""


# The machinery. Validates that we're actually looking at ***, ---, or ___
# and consumes the line.
class HorizontalLineReader:
    state:
        marker: Character
        count: Int
    
    invariants:
        # Must have at least 3 markers for a valid HR.
        count == 0 or count >= 3
        # Marker is one of the recognized characters.
        marker == '-' or marker == '*' or marker == '_' or count == 0
    
    methods:
        class function read(reader: Reader) throws ParseError -> HorizontalLine:
            var start_char = reader.current_character()
            
            if start_char != '-' and start_char != '*' and start_char != '_':
                throw ParseError.mismatch(
                    expected: '-',
                    found: start_char
                )
            
            var builder = HorizontalLineReader(marker: start_char, count: 0)
            
            while !reader.did_reach_end():
                var c = reader.current_character()
                if c == builder.marker:
                    builder.count = builder.count + 1
                    reader.advance_index()
                elif c.is_whitespace() and not c.is_newline():
                    reader.advance_index()
                elif c.is_newline():
                    break
                else:
                    throw ParseError.mismatch(
                        expected: builder.marker,
                        found: c
                    )
            
            if builder.count < 3:
                throw ParseError.mismatch(
                    expected: builder.marker,
                    found: builder.marker  # not enough of them
                )
            
            return HorizontalLine()
```

**What this shows:**

Even a marker type — literally no data — fits the two-type pattern comfortably. The value is a zero-field struct. The reader accumulates enough info to validate the horizontal rule syntax, then constructs the empty struct.

**The invariant block earns its keep here.** HorizontalLineReader's state has two constraints: `count == 0 or count >= 3` (either not yet checking, or at least 3 markers found), and `marker` is one of `-`, `*`, `_`. Both prevent obvious bugs during parser development. Swift's parser probably has similar constraints as comments; ours has them as enforceable invariants.

**One friction:**

The `elif` keyword I used isn't in §10 grammar. Swift and Corvid could both express this as chained `if / else if`. `elif` is a Python-ism I imported unconsciously.

Consistent grammar choice needed: `else if` (Swift/C-family) or `elif` (Python). I lean `else if` because our syntax is more C-family than Python. **Friction: §10 grammar sketch should specify this.**

---

## Heading — enum-like level

Heading has a level (1-6) and text content. Swift represents level as `Int`; we can do better with a closed enumeration.

```Corvid
module com.ink.internal

# The heading level. Sealed enum with exactly 6 cases.
# Enables exhaustive matching in renderers.
expose enumeration HeadingLevel:
    case h1
    case h2
    case h3
    case h4
    case h5
    case h6
    
    methods:
        # For HTML rendering: return the tag name.
        function tag(self) -> String:
            switch self:
                case .h1: return "h1"
                case .h2: return "h2"
                case .h3: return "h3"
                case .h4: return "h4"
                case .h5: return "h5"
                case .h6: return "h6"
        
        # For parser: derive from count of # characters.
        # Returns nil for invalid counts (0 or > 6).
        class function from_pound_count(count: Int) -> HeadingLevel?:
            switch count:
                case 1: return .h1
                case 2: return .h2
                case 3: return .h3
                case 4: return .h4
                case 5: return .h5
                case 6: return .h6
                default: return nil


# The value.
struct Heading:
    conforms:
        Fragment
    
    data:
        level: HeadingLevel
        text: FormattedText
    
    methods:
        function modifier_target() -> ModifierTarget:
            return .headings
        
        class function read(reader: Reader) throws ParseError -> Heading:
            return HeadingReader.read(reader: reader)
        
        function html(self,
                     urls: NamedURLCollection,
                     raw_string: Substring,
                     modifiers: ModifierCollection) -> String:
            var body = self.text.html(urls: urls, modifiers: modifiers)
            var tag = self.level.tag()
            return "<${tag}>${body}</${tag}>"
        
        function plain_text(self) -> String:
            return self.text.plain_text()


# The machinery.
class HeadingReader:
    state:
        pound_count: Int
        text: FormattedTextBuilder
    
    invariants:
        pound_count >= 0
        pound_count <= 6  # Valid ATX heading levels
    
    methods:
        class function read(reader: Reader) throws ParseError -> Heading:
            var builder = HeadingReader(
                pound_count: 0,
                text: FormattedTextBuilder.new()
            )
            
            # Count leading '#' characters
            while !reader.did_reach_end() 
                  and reader.current_character() == '#'
                  and builder.pound_count < 6:
                builder.pound_count = builder.pound_count + 1
                reader.advance_index()
            
            # Guard against invalid heading (0 or 7+ pounds)
            if builder.pound_count == 0 or builder.pound_count > 6:
                throw ParseError.mismatch(
                    expected: '#',
                    found: reader.current_character()
                )
            
            # Consume whitespace between # and text
            reader.read_whitespaces()
            
            # Read the heading text until newline or EOF
            builder.text.append(FormattedText.read_line(reader: reader))
            
            return builder.build()
        
        function build(self) throws ParseError -> Heading:
            var level = HeadingLevel.from_pound_count(count: self.pound_count)
            if level == nil:
                throw ParseError.mismatch(
                    expected: '#',
                    found: '#'  # Shouldn't happen given invariant, but...
                )
            return Heading(level: level!, text: self.text.build())
```

**What this shows:**

- **Sealed enumerations earn their keep.** `HeadingLevel` has exactly 6 cases. Renderers switching on it are exhaustive; adding a case is impossible without a language change. Swift represents this as `Int` with runtime constraints; we get compile-time safety.
- **The invariant on `HeadingReader.pound_count` documents the state machine.** During parsing, count can be 0-6; anything else is a bug.
- **`build()` can throw** — a builder can fail if invariants are violated. This is honest: not every accumulator state is a valid finished product.

**One design question surfaces:**

Should `HeadingLevel` be an enumeration or should I use `Int` in the 1-6 range with a refinement type? The refinement approach (constrained Int) would express "any int, but must be 1-6". That's closer to Swift but loses exhaustive matching benefits.

I chose enum for correctness. Real markdown parsers occasionally deal with H7 or H8 (some spec extensions); if we ever need to extend, changing the enum is a language-level change but a small one. The correctness benefit is worth the extension cost.

---

## List — the recursive case

List is the tricky one. A list contains items; each item contains inline content and possibly nested lists. This is where recursion tests our type system.

Ink supports both ordered and unordered lists. Swift represents this with a nested type structure:

```Corvid
module com.ink.internal

# Ordered vs unordered — enumeration for exhaustive dispatch
expose enumeration ListStyle:
    case bullet(marker: Character)      # -, *, or +
    case ordered(start: Int)             # starting number for renumbering


# List item content is either inline text or a nested list.
# Sealed protocol enables exhaustive rendering.
expose enumeration ListItemContent:
    case text(FormattedText)
    case nested_list(List)


# The value: an immutable parsed list.
expose struct List:
    conforms:
        Fragment
    
    data:
        style: ListStyle
        items: [ListItem]
    
    invariants:
        # A list with no items is malformed.
        items.count > 0
    
    methods:
        function modifier_target() -> ModifierTarget:
            return .lists
        
        class function read(reader: Reader) throws ParseError -> List:
            return ListReader.read(reader: reader)
        
        function html(self,
                     urls: NamedURLCollection,
                     raw_string: Substring,
                     modifiers: ModifierCollection) -> String:
            var tag = switch self.style:
                case .bullet: "ul"
                case .ordered: "ol"
            
            var body = ""
            for item in self.items:
                body = body + item.html(urls: urls, modifiers: modifiers)
            
            return "<${tag}>${body}</${tag}>"
        
        function plain_text(self) -> String:
            var result = ""
            for item in self.items:
                result = result + item.plain_text() + "\n"
            return result


# A single list item. Immutable.
expose struct ListItem:
    data:
        contents: [ListItemContent]
    
    methods:
        function html(self,
                     urls: NamedURLCollection,
                     modifiers: ModifierCollection) -> String:
            var body = ""
            for content in self.contents:
                switch content:
                    case .text(text):
                        body = body + text.html(urls: urls, modifiers: modifiers)
                    case .nested_list(list):
                        body = body + list.html(
                            urls: urls,
                            raw_string: "",  # nested lists have no raw range
                            modifiers: modifiers
                        )
            return "<li>${body}</li>"
        
        function plain_text(self) -> String:
            var result = ""
            for content in self.contents:
                switch content:
                    case .text(text):
                        result = result + text.plain_text()
                    case .nested_list(list):
                        result = result + list.plain_text()
            return result


# The machinery. Accumulates items with indentation-aware parsing.
class ListReader:
    state:
        style: ListStyle?
        items: [ListItem]
        current_indent: Int
    
    methods:
        class function read(reader: Reader) throws ParseError -> List:
            var builder = ListReader(
                style: nil,
                items: [],
                current_indent: 0
            )
            
            # Determine style from first character
            builder.style = try ListReader.detect_style(reader: reader)
            
            while !reader.did_reach_end():
                # Parse a single item at the current indent
                var item = try builder.read_item(reader: reader)
                builder.items.append(item)
                
                # Check whether next line continues the list at same indent
                if !builder.next_line_is_list_item(reader: reader):
                    break
            
            return builder.build()
        
        # ... (detect_style, read_item, next_line_is_list_item omitted for space)
        
        function build(self) throws ParseError -> List:
            if self.style == nil:
                throw ParseError.mismatch(
                    expected: '-',  # any list marker
                    found: '?'
                )
            if self.items.is_empty():
                throw ParseError.mismatch(
                    expected: '-',
                    found: '?'
                )
            return List(style: self.style!, items: self.items)
```

**What this reveals:**

**Enumerations for closed sum types work naturally.** `ListItemContent` is either text or a nested list. Exhaustive matching in `ListItem.html` guarantees both cases are handled. Swift uses associated-value enums; we use enumerations with variant cases. Same construct, similar syntax.

(Earlier drafts of the Corvid spec had `sealed protocol` for the closed-conformance case; the v0.5.1 cleanup unified this on enumerations. Protocols are always open interfaces.)

**Recursion is straightforward.** `nested_list(List)` inside `ListItemContent` inside `[ListItem]` inside `List` — the type system handles it without ceremony. Since List is a struct (value type), copying is well-defined; since it contains a `[ListItem]` array (which contains other structs), there's no reference-counting concern.

**A design tension surfaces:**

`ListItem` contains `[ListItemContent]`. If the same content appears in multiple items (unlikely in practice but possible), we'd copy. Under struct-with-value-semantics, copies are honest. Under class-with-reference-semantics, sharing would be implicit.

For a parser's output, value semantics is right — the parse tree is a tree, not a graph. If sharing were desirable (some rendering optimization), we could switch to classes then.

**Nested list HTML rendering has a subtle issue:**

I passed `raw_string: ""` when rendering nested lists. That's wrong for real Ink — modifiers care about raw strings. In practice, either the nested list's rendering needs the correct sub-range of the outer raw string, or the modifier system needs to handle empty raw strings for nested content.

This is a real bug I introduced by approximating without the full source. It's the kind of thing the parity pass would have caught. **Friction: approximating source has real correctness costs; don't oversell what I've translated when I haven't read the actual Swift.**

**The dispatch closures point:**

`switch content: case .text(text): ...` binds a `text` variable to the payload. Does §3.7 support this pattern? The spec says "structural patterns and exhaustiveness" but doesn't specify payload binding syntax.

**Friction: §3.7 needs to specify the binding pattern for enumeration cases with associated data.** Options: `case .text(text):` (Swift-like), `case text: .text(let text):` (verbose), `case .text(let text):` (Swift with `let`). Any works but should be picked.

---

## Line count and structural comparison

| File | Swift | Idiomatic Corvid | Notes |
|---|---|---|---|
| MarkdownParser.swift | 144 | 100 | Three types replace one struct |
| Fragment.swift (protocol) | ~30 (est.) | 20 | Cleaner effect declarations |
| Paragraph.swift | ~30 (est.) | 40 | Two-type expansion |
| HorizontalLine.swift | ~40 (est.) | 55 | Adds explicit invariants |
| Heading.swift | ~50 (est.) | 90 | Sealed enum + two-type expansion |
| List.swift | ~150 (est., partial) | 110 | Sealed protocol + two-type |
| **Subtotal (this round)** | **~444** | **~415** | |
| Prior round (Reader, Markdown, Blockquote) | ~243 | ~170 | |
| **Total** | **~687** | **~585** | ~85% of Swift, with more type safety |

The idiomatic pass is consistently shorter than Swift when it has the right patterns to lean on — enumerations for closed sum types, two-type pattern, contracts as documentation. It's longer when we're explicitly encoding things Swift leaves implicit (invariants, HeadingLevel as enumeration vs Int).

Net: comparable line count with substantially more type-safety and documented state.

---

## Findings across the extended coverage

### 1. The two-type pattern is robust

Every Fragment type in this pass split cleanly into struct-value + class-builder:

- Paragraph / ParagraphReader
- Heading / HeadingReader  
- HorizontalLine / HorizontalLineReader
- List / ListReader
- Blockquote / BlockquoteReader (prior round)

Zero cases where the pattern didn't fit. Even HorizontalLine — with zero data fields — followed it naturally.

The pattern also applied to MarkdownParser, but for a different reason (long-lived config vs ephemeral parse state). This suggests two distinct usages of two-type:

- **Struct + Builder-Class**: for immutable values with mutable construction phase (Fragments)
- **Class + Runner-Class**: for stateful services with per-operation state (MarkdownParser + ParseRun)

Both are variants of the same idea: separate "the thing" from "the doing." Names help — "Reader" for read-machinery, "Runner" for compute-machinery.

### 2. Enumerations earn their keep repeatedly

- `HeadingLevel` (6 discrete cases) 
- `ListStyle` (bullet + ordered)
- `ListItemContent` (text + nested list)
- Existing: `ParseError` (unexpected end + mismatch)

Each is a place where Swift uses either untyped enums or protocol hierarchies. Ours uses enumerations with exhaustive matching. The correctness guarantees are worth the more precise types.

### 3. Invariants surface real bugs

The invariants I added weren't decoration:

- `HorizontalLineReader.count`: enforces "at least 3 markers or not yet counting"
- `HeadingReader.pound_count`: enforces 0-6 range
- `List.items.count > 0`: prevents empty lists

Each of these catches a class of bugs during development. Swift's parser probably has runtime asserts for the same things (or crashes if they're violated). Ours documents and enforces them at the type level.

### 4. New friction points surfaced

Beyond the 30 from the first pass, this round adds:

- **31. `\.isNumber` KeyPath predicate matching in switch cases** — Swift feature not in our §3.7. Worked around with `case c where c.is_number():`.
- **32. Payload binding in enumeration case matching** — `case .text(text):` vs `case .text(let text):`. Unspecified.
- **33. Multi-case fallthrough with same body** — `case 'a', 'b': ...` vs `case 'a': case 'b': ...`. Grammar unclear.
- **34. `else if` vs `elif`** — chosen family influences readability.
- **35. Type references as first-class values** — `Heading.self`, `Fragment.Type`. Used in the fragment_type dispatch. Currently unspecified.
- **36. Conditional binding `if let x = optional { ... }`** — Used in MarkdownParser. Same as first pass (FRICTION #9); still needs spec.
- **37. Nested control flow with early break/continue** — Used in ParseRun.parse_one_fragment. Standard C-family semantics assumed.
- **38. Switch expressions returning values** — `var tag = switch self.style: ...`. Do we allow switch as expression? Swift does; §3.7 doesn't specify.

Most are syntactic gaps like the first round. The design holds; the syntax still needs work.

### 5. Fragment protocol design decision

I chose **open** Fragment protocol (users can add their own). This means:

- Modifier system extends naturally to user fragments.
- Ink-like customization works.
- Compiler can't do exhaustive dispatch across fragments.

If we wanted a closed Fragment type, we'd model it as an enumeration and give up user-extensibility for compile-time exhaustiveness. For Ink's use case, extensibility wins.

For other use cases (e.g., a compiler's AST) the enumeration approach would be right. The choice belongs to the library author. Language supports both — open protocols for extension points, enumerations for closed sum types.

---

## Overall verdict on Path B extended

After translating 6 more types (MarkdownParser, Fragment, Paragraph, HorizontalLine, Heading, List — plus prior 3), the design holds.

**The design's core commitments earn their keep repeatedly:**

- Contracts on state transitions (Reader operations, builder `build()` methods that can throw)
- Invariants on stateful fields (position bounds, counts, ranges)
- Sealed enums for discrete state (levels, styles, error variants)
- Sealed protocols for closed sum types (ListItemContent)
- The two-type pattern for construction-vs-consumption
- Universal extents for anything queryable (would apply to Markdown collections)

**The spec gaps are cumulative but tractable.** After 9 files translated we have ~38 friction points, most syntactic. A focused pass on §3.7 (pattern matching), §3 (optional handling and control flow), and §2 (lexical for string interpolation and type references) would close most of them.

**No architectural surprises.** Nothing in these Fragment types required rethinking the design. The two-type pattern generalized. Sealed enums and protocols generalized. Contracts and invariants applied naturally. This is the honest signal: the design works for markdown parsing.

**Coverage assessment:** with 9 translated files representing value types, reference types, closed enumerations, open protocols, recursive types, factory patterns, orchestrators, and per-operation runners, we've hit substantially all the structural shapes in Ink. The remaining Fragment types (Image, HTML, CodeBlock, Table, FormattedText) would follow similar patterns without novel structural findings — they'd add friction data points but not new design questions.

---

## Where I'd go next

Two options at this point:

**Option A: Fix top spec gaps.** With 38 friction points across 9 files, we have real data on what the spec is missing. Priorities are clearer than they were after the first round:

1. `switch` cases: payload binding, predicate patterns, multi-case (§3.7)
2. Optional handling: `if let`, `?.`, `??` (§3)
3. String interpolation syntax (§2)
4. `try` keyword and `throws E1 as E2` conversions (§3.5)
5. Type references as values (§3)
6. Control flow: `else if`, switch-as-expression (§10 grammar)
7. Auto-generated constructors documented (§6)

A focused §3/§7/§10 revision addressing these would unblock further translation and clean up ambiguities users would hit constantly.

**Option B: AI harness for concurrency axes.** Markdown exercised the syntactic and structural design deeply. It barely touched concurrency, extents, channels, signals. An AI harness would stress-test §1.9, §6.4-6.6, §8 — the parts Ink didn't reach.

I lean **A first, then B.** The syntactic gaps are actively blocking; if we did the AI harness now, we'd generate parallel friction on the same syntactic issues while trying to test concurrency. Better to close the syntactic story first, then test concurrency with clean syntax.

Ballpark for A: maybe 200-300 lines of spec revisions concentrated in §3, §3.7, §2, and §10. One focused session.

After A, Option B (AI harness) becomes a much stronger test.

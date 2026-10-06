// Pocket MOBA, translated from cards.cxc.
// This file is the TypeScript side of the cxc / rust / ts three-way structural
// comparison. The point is lifecycle shape, not byte parity against the cxc
// binary, so each cxc atom is a class with a phase union, each `<: msg -> p
// then: ...` arm is a method that updates `this.phase` and runs the then-body,
// and `|>` sends become direct method calls. Child-to-parent events (`:>`
// broadcasts in cxc) are modelled as listener interfaces each atom registers
// against. Observations that do not participate in state transitions are
// inlined as getters or helper methods on the owning class.

// ================================================================
// Enums (string-literal unions so names survive in traces).
// ================================================================

type CardType = "hero" | "neutral" | "item" | "scroll";

type EffectKind =
  | "none"
  | "damage_all"
  | "heal_all"
  | "silence_all"
  | "buff_all"
  | "clear_all"
  | "attach_thorn_all"
  | "cross_damage_all"
  | "move_from_next_lane_front"
  | "draw_n_discard_weakest"
  | "damage_cap"
  | "reflect_bonus"
  | "attack_bypass"
  | "tower_bonus";

type EffectScope = "own_side" | "enemy_side";

// ================================================================
// Data records (no lifecycle of their own).
// ================================================================

interface DeployEffect {
  kind: EffectKind;
  scope: EffectScope;
  amount: number;
}

interface CombatEffect {
  kind: EffectKind;
  amount: number;
}

interface Card {
  name: string;
  type: CardType;
  attack: number;
  hp: number;
  coin: number;
  pre_deploy: DeployEffect;
  on_deploy: DeployEffect;
  combat_effect: CombatEffect;
}

// Shared sentinel the cxc source uses to make `.at(0) ?? _sentinel_card`
// fallbacks total.
const NO_EFFECT: DeployEffect = { kind: "none", scope: "own_side", amount: 0 };
const NO_COMBAT: CombatEffect = { kind: "none", amount: 0 };
const SENTINEL_CARD: Card = {
  name: "?",
  type: "hero",
  attack: 0,
  hp: 0,
  coin: 0,
  pre_deploy: NO_EFFECT,
  on_deploy: NO_EFFECT,
  combat_effect: NO_COMBAT,
};

// ================================================================
// SeededPrng. The cxc source uses the stdlib SeededPrng(seed: 42); here a
// plain LCG keeps the shape, since byte parity is not required.
// ================================================================

class SeededPrng {
  private state: number;
  constructor(seed: number) {
    this.state = seed >>> 0;
  }
  below(n: number): number {
    this.state = (Math.imul(this.state, 1664525) + 1013904223) >>> 0;
    return this.state % n;
  }
}

// ================================================================
// Listener interfaces. Each one mirrors the broadcasts a child atom emits
// upward, so the parent can register itself and route the event back into
// its own arms (the TS analogue of the cxc `lanes.unit_gone` pattern).
// ================================================================

interface DeckListener {
  on_deck_drew_hand?(card: Card, seat: number): void;
  on_deck_hand_empty?(seat: number): void;
  on_deck_drew_market?(card: Card): void;
  on_deck_market_empty?(): void;
}

interface HandListener {
  on_hand_draw_done(): void;
}

interface MarketListener {
  on_market_refill_done(): void;
}

interface PlayerListener {
  on_player_draw_done(seat: number): void;
  on_player_card_deployed(card: Card, seat: number): void;
  on_player_deploy_done(seat: number): void;
  on_player_tower_quota_done(seat: number): void;
  on_player_hand_cap_done(seat: number): void;
  on_player_hand_overflow_discard(card: Card, seat: number): void;
}

interface LaneListener {
  on_lane_unit_gone(card: Card, seat: number, hp: number): void;
  on_lane_tower_lost(seat: number, card: Card): void;
  on_lane_combat_done(): void;
}

// ================================================================
// PlayableUnit atom. `dead` is a phase of its own so Lane filters can still
// find 0-hp units when it clears them.
// ================================================================

type PlayableUnitPhase = "alive" | "dead" | "end";

class PlayableUnit {
  phase: PlayableUnitPhase = "alive";
  card: Card;
  owner_seat: number;
  current_hp: number;
  silenced: boolean = false;
  thorn_attached: boolean = false;
  atk_bonus: number = 0;

  constructor(card: Card, owner_seat: number) {
    this.card = card;
    this.owner_seat = owner_seat;
    this.current_hp = card.hp;
  }

  get total_attack(): number {
    return this.card.attack + this.atk_bonus;
  }

  get reflect_bonus(): number {
    if (this.card.combat_effect.kind === "reflect_bonus") {
      return this.card.combat_effect.amount;
    }
    if (this.thorn_attached) return 1;
    return 0;
  }

  effective_damage(amount: number): number {
    if (this.card.combat_effect.kind === "damage_cap") {
      return Math.min(amount, this.card.combat_effect.amount);
    }
    return amount;
  }

  take_damage(amount: number): void {
    if (this.phase !== "alive") return;
    const ed = this.effective_damage(amount);
    if (ed < this.current_hp) {
      this.current_hp -= ed;
      return;
    }
    this.phase = "dead";
    this.current_hp = 0;
  }

  heal(amount: number): void {
    if (this.phase !== "alive") return;
    this.current_hp = Math.min(this.current_hp + amount, this.card.hp);
  }

  silence(): void {
    if (this.phase !== "alive") return;
    this.silenced = true;
  }

  buff_atk(amount: number): void {
    if (this.phase !== "alive") return;
    this.atk_bonus += amount;
  }

  attach_thorn(): void {
    if (this.phase !== "alive") return;
    this.thorn_attached = true;
  }
}

// ================================================================
// LaneSide atom. One team's half of a lane: its units and its tower.
// ================================================================

type LaneSidePhase = "idle" | "end";

class LaneSide {
  phase: LaneSidePhase = "idle";
  team: number;
  units: PlayableUnit[] = [];
  tower_placed: boolean = false;
  tower_card: Card = SENTINEL_CARD;
  tower_hp: number = 0;

  constructor(team: number) {
    this.team = team;
  }

  append(card: Card): void {
    if (this.phase !== "idle") return;
    this.units = [...this.units, new PlayableUnit(card, this.team)];
  }

  damage_all(amount: number): void {
    if (this.phase !== "idle") return;
    this.units.forEach(u => u.take_damage(amount));
  }

  heal_all(amount: number): void {
    if (this.phase !== "idle") return;
    this.units.forEach(u => u.heal(amount));
  }

  silence_all(): void {
    if (this.phase !== "idle") return;
    this.units.forEach(u => u.silence());
  }

  attach_thorn_all(): void {
    if (this.phase !== "idle") return;
    this.units.forEach(u => u.attach_thorn());
  }

  buff_all(amount: number): void {
    if (this.phase !== "idle") return;
    this.units.forEach(u => u.buff_atk(amount));
  }

  remove_by_name(name: string): void {
    if (this.phase !== "idle") return;
    this.units = this.units.filter(u => u.card.name !== name);
  }

  filter_dead(): void {
    if (this.phase !== "idle") return;
    this.units = this.units.filter(u => u.current_hp > 0);
  }

  clear_units(): void {
    if (this.phase !== "idle") return;
    this.units = [];
  }

  place_tower(card: Card): void {
    if (this.phase !== "idle") return;
    if (this.tower_placed) return;
    this.tower_placed = true;
    this.tower_card = card;
    this.tower_hp = 5;
  }

  clear_tower(): void {
    if (this.phase !== "idle") return;
    this.tower_placed = false;
    this.tower_hp = 0;
  }

  damage_tower(amount: number): void {
    if (this.phase !== "idle") return;
    this.tower_hp = Math.max(this.tower_hp - amount, 0);
  }

  absorb_overkill_if_unopposed(overkill: number): void {
    if (this.phase !== "idle") return;
    const alive = this.units.filter(u => u.current_hp > 0).length;
    if (this.tower_placed && alive === 0) {
      this.tower_hp = Math.max(this.tower_hp - overkill, 0);
    }
  }
}

// ================================================================
// Lane atom. Holds two LaneSides and runs combat. Cards leaving the lane
// are reported back via `on_lane_unit_gone`.
// ================================================================

type LanePhase =
  | "idle"
  | "preparing"
  | "combatting"
  | "unopposed"
  | "destroying"
  | "finalizing"
  | "end";

class Lane {
  phase: LanePhase = "idle";
  idx: number;
  side_0: LaneSide = new LaneSide(0);
  side_1: LaneSide = new LaneSide(1);
  combat_idx: number = 0;
  overkill_0: number = 0;
  overkill_1: number = 0;
  listener: LaneListener | null = null;

  constructor(idx: number) {
    this.idx = idx;
  }

  side(team: number): LaneSide {
    return team === 0 ? this.side_0 : this.side_1;
  }

  enemy_side(team: number): LaneSide {
    return team === 0 ? this.side_1 : this.side_0;
  }

  front(team: number): PlayableUnit | undefined {
    return this.side(team).units[this.combat_idx];
  }

  behind(team: number): PlayableUnit | undefined {
    return this.side(team).units[this.combat_idx + 1];
  }

  get paired(): boolean {
    return (
      this.combat_idx < this.side_0.units.length &&
      this.combat_idx < this.side_1.units.length
    );
  }

  get tower_lost_seat(): number {
    if (this.side_0.tower_placed && this.side_0.tower_hp === 0) return 0;
    if (this.side_1.tower_placed && this.side_1.tower_hp === 0) return 1;
    return -1;
  }

  tower_damage(team: number): number {
    const f = this.front(team);
    if (!f) return 0;
    const bonus =
      f.card.combat_effect.kind === "tower_bonus"
        ? f.card.combat_effect.amount
        : 0;
    return f.total_attack + bonus;
  }

  private emit_unit_gone(card: Card, seat: number, hp: number): void {
    this.listener?.on_lane_unit_gone(card, seat, hp);
  }

  // place_hero collapses several `idle <: place_hero` arms into one method,
  // keyed by card type / pre_deploy / on_deploy — in the same order the cxc
  // source declares them so first-match semantics match.
  place_hero(card: Card, team: number): void {
    if (this.phase !== "idle") return;

    // Scroll with heal_all on_deploy (Return Scroll): heal silently.
    if (card.type === "scroll" && card.on_deploy.kind === "heal_all") {
      this.side(team).heal_all(card.on_deploy.amount);
      return;
    }
    // Item with attach_thorn on_deploy (Thorn Armor).
    if (card.type === "item" && card.on_deploy.kind === "attach_thorn_all") {
      this.side(team).attach_thorn_all();
      this.emit_unit_gone(card, team, 0);
      return;
    }
    // Any other item or scroll: discard.
    if (card.type === "item" || card.type === "scroll") {
      this.emit_unit_gone(card, team, 0);
      return;
    }
    // Pre-append clear (Fallen Warlock).
    if (
      card.pre_deploy.kind === "clear_all" &&
      card.pre_deploy.scope === "enemy_side"
    ) {
      this.enemy_side(team).clear_units();
      this.side(team).append(card);
      return;
    }
    // Post-append dispatch by on_deploy.kind.
    switch (card.on_deploy.kind) {
      case "damage_all":
        this.side(team).append(card);
        this.enemy_side(team).damage_all(card.on_deploy.amount);
        return;
      case "heal_all":
        this.side(team).append(card);
        this.side(team).heal_all(card.on_deploy.amount);
        return;
      case "silence_all":
        this.side(team).append(card);
        this.enemy_side(team).silence_all();
        return;
      case "buff_all":
        this.side(team).append(card);
        this.side(team).buff_all(card.on_deploy.amount);
        return;
      default:
        this.side(team).append(card);
    }
  }

  remove_hero_by_name(team: number, name: string): void {
    if (this.phase !== "idle") return;
    this.side(team).remove_by_name(name);
  }

  cross_damage(enemy_team: number, amount: number): void {
    if (this.phase !== "idle") return;
    this.side(enemy_team).damage_all(amount);
  }

  place_tower(team: number, card: Card): void {
    if (this.phase !== "idle") return;
    this.side(team).place_tower(card);
  }

  begin_combat(): void {
    if (this.phase !== "idle") return;
    // `preparing` reports units already killed by deploy damage, then
    // `begin_pairing` drops them from both sides.
    this.phase = "preparing";
    this.side_0.units
      .filter(u => u.current_hp === 0)
      .forEach(u => this.emit_unit_gone(u.card, u.owner_seat, 0));
    this.side_1.units
      .filter(u => u.current_hp === 0)
      .forEach(u => this.emit_unit_gone(u.card, u.owner_seat, 0));
    this.begin_pairing();
  }

  private begin_pairing(): void {
    if (this.phase !== "preparing") return;
    this.phase = "combatting";
    this.side_0.filter_dead();
    this.side_1.filter_dead();
    this.combat_idx = 0;
    this.overkill_0 = 0;
    this.overkill_1 = 0;
    this.pair_step();
  }

  private pair_step(): void {
    if (this.phase !== "combatting") return;
    while (this.paired) {
      const f0 = this.front(0)!;
      const f1 = this.front(1)!;
      // Side-0 Marksman (attack_bypass) skips the enemy front and hits behind.
      if (
        f0.card.combat_effect.kind === "attack_bypass" &&
        this.combat_idx + 1 < this.side_1.units.length
      ) {
        const b1 = this.behind(1)!;
        b1.take_damage(f0.total_attack + b1.reflect_bonus);
        f0.take_damage(f1.total_attack + f1.reflect_bonus);
        this.combat_idx += 1;
        continue;
      }
      // Side-1 Marksman.
      if (
        f1.card.combat_effect.kind === "attack_bypass" &&
        this.combat_idx + 1 < this.side_0.units.length
      ) {
        const b0 = this.behind(0)!;
        b0.take_damage(f1.total_attack + b0.reflect_bonus);
        f1.take_damage(f0.total_attack + f0.reflect_bonus);
        this.combat_idx += 1;
        continue;
      }
      // Standard pair fight. Overkill accumulates for the tower hit.
      this.overkill_0 += Math.max(f0.total_attack - f1.current_hp, 0);
      this.overkill_1 += Math.max(f1.total_attack - f0.current_hp, 0);
      f0.take_damage(f1.total_attack + f1.reflect_bonus);
      f1.take_damage(f0.total_attack + f0.reflect_bonus);
      this.combat_idx += 1;
    }
    // pair_step -> unopposed. The `-> unopposed then: self :> tower_hit`
    // phase-entry hook triggers tower_hit after the overkill absorbs land.
    this.phase = "unopposed";
    this.side_1.absorb_overkill_if_unopposed(this.overkill_0);
    this.side_0.absorb_overkill_if_unopposed(this.overkill_1);
    this.tower_hit();
  }

  // tower_hit implements the unopposed phase loop: damage a tower, run
  // check_towers, loop until both sides are exhausted or a tower falls.
  private tower_hit(): void {
    while (this.phase === "unopposed") {
      // `tower_hit -> finalizing` when both sides are exhausted.
      if (
        this.combat_idx >= this.side_0.units.length &&
        this.combat_idx >= this.side_1.units.length
      ) {
        this.phase = "finalizing";
        this.side_0.units
          .filter(u => u.current_hp === 0)
          .forEach(u => this.emit_unit_gone(u.card, u.owner_seat, 0));
        this.side_1.units
          .filter(u => u.current_hp === 0)
          .forEach(u => this.emit_unit_gone(u.card, u.owner_seat, 0));
        this.finalize();
        return;
      }
      // Damage one tower from this step's attacker side.
      if (this.combat_idx < this.side_0.units.length) {
        this.side_1.damage_tower(this.tower_damage(0));
        this.combat_idx += 1;
      } else {
        this.side_0.damage_tower(this.tower_damage(1));
        this.combat_idx += 1;
      }
      // `check_towers` arm.
      const lost = this.tower_lost_seat;
      if (lost >= 0) {
        this.phase = "destroying";
        this.listener?.on_lane_tower_lost(lost, this.side(lost).tower_card);
        this.side_0.units.forEach(u =>
          this.emit_unit_gone(u.card, u.owner_seat, u.current_hp),
        );
        this.side_1.units.forEach(u =>
          this.emit_unit_gone(u.card, u.owner_seat, u.current_hp),
        );
        this.finish_destroy(lost);
        // finish_destroy -> unopposed. Phase-entry hook re-sends tower_hit,
        // which is the next iteration of this loop.
        this.phase = "unopposed";
      }
      // Otherwise `check_towers -> unopposed then: self :> tower_hit`, which
      // is the loop continuing on its own.
    }
  }

  private finish_destroy(seat: number): void {
    this.side(seat).clear_tower();
    this.side_0.clear_units();
    this.side_1.clear_units();
  }

  private finalize(): void {
    if (this.phase !== "finalizing") return;
    this.side_0.filter_dead();
    this.side_1.filter_dead();
    this.phase = "idle";
    this.listener?.on_lane_combat_done();
  }
}

// ================================================================
// Deck atom. Holds draw and discard piles, serves try_draw_hand /
// try_draw_market, and broadcasts drew_* / *_empty to listeners.
// ================================================================

type DeckPhase = "idle" | "end";

class Deck {
  phase: DeckPhase = "idle";
  cards: Card[] = [];
  discard: Card[] = [];
  prng: SeededPrng;
  listeners: DeckListener[] = [];

  constructor(prng: SeededPrng) {
    this.prng = prng;
  }

  seed(initial: Card[]): void {
    if (this.phase !== "idle") return;
    this.cards = [...initial];
  }

  add_discard(card: Card): void {
    if (this.phase !== "idle") return;
    this.discard = [...this.discard, card];
  }

  try_draw_hand(seat: number): void {
    if (this.phase !== "idle") return;
    // Refill cards from discard, then retry.
    if (this.cards.length === 0 && this.discard.length > 0) {
      this.cards = this.discard;
      this.discard = [];
      this.try_draw_hand(seat);
      return;
    }
    if (this.cards.length === 0) {
      for (const l of this.listeners) l.on_deck_hand_empty?.(seat);
      return;
    }
    const idx = this.prng.below(this.cards.length);
    const card = this.cards[idx] ?? SENTINEL_CARD;
    this.cards = this.cards.filter(c => c.name !== card.name);
    for (const l of this.listeners) l.on_deck_drew_hand?.(card, seat);
  }

  try_draw_market(): void {
    if (this.phase !== "idle") return;
    if (this.cards.length === 0 && this.discard.length > 0) {
      this.cards = this.discard;
      this.discard = [];
      this.try_draw_market();
      return;
    }
    if (this.cards.length === 0) {
      for (const l of this.listeners) l.on_deck_market_empty?.();
      return;
    }
    const idx = this.prng.below(this.cards.length);
    const card = this.cards[idx] ?? SENTINEL_CARD;
    this.cards = this.cards.filter(c => c.name !== card.name);
    for (const l of this.listeners) l.on_deck_drew_market?.(card);
  }
}

// ================================================================
// Hand atom. Per-player hand: pulls from Deck until it holds 3 cards.
// ================================================================

type HandPhase = "idle" | "drawing" | "end";

class Hand implements DeckListener {
  phase: HandPhase = "idle";
  cards: Card[] = [];
  deck: Deck;
  owner_seat: number;
  listener: HandListener | null = null;

  constructor(deck: Deck, owner_seat: number) {
    this.deck = deck;
    this.owner_seat = owner_seat;
    deck.listeners.push(this);
  }

  begin_draw(): void {
    if (this.phase !== "idle") return;
    this.phase = "drawing";
    this.pull();
  }

  private pull(): void {
    if (this.phase !== "drawing") return;
    if (this.cards.length < 3) {
      this.deck.try_draw_hand(this.owner_seat);
      return;
    }
    // `drawing <: pull -> idle then: :> draw_done`.
    this.phase = "idle";
    this.listener?.on_hand_draw_done();
  }

  on_deck_drew_hand(card: Card, seat: number): void {
    if (this.phase !== "drawing") return;
    if (seat !== this.owner_seat) return;
    this.cards = [...this.cards, card];
    this.pull();
  }

  on_deck_hand_empty(seat: number): void {
    if (this.phase !== "drawing") return;
    if (seat !== this.owner_seat) return;
    this.phase = "idle";
    this.listener?.on_hand_draw_done();
  }

  // `* <: append / remove_by_name` — any phase, used by lane-return and
  // tower-return flows and by the Occultist deploy effect.
  append(card: Card): void {
    this.cards = [...this.cards, card];
  }

  remove_by_name(name: string): void {
    this.cards = this.cards.filter(c => c.name !== name);
  }
}

// ================================================================
// Player atom. Owns a Hand, drives draw / deploy / tower-quota / hand-cap
// against three shared lanes.
// ================================================================

type PlayerPhase = "idle" | "deploying" | "placing_towers" | "capping_hand" | "end";

class Player implements DeckListener, HandListener {
  phase: PlayerPhase = "idle";
  seat: number;
  deck: Deck;
  lane0: Lane;
  lane1: Lane;
  lane2: Lane;
  hand: Hand;
  won: Card[] = [];
  mulligans: number = 0;
  total_deploys: number = 0;
  total_auction_wins: number = 0;
  towers_placed: number = 0;
  towers_lost: number = 0;
  // Occultist deploy state. Nonzero only mid-Occultist-effect.
  occult_remaining: number = 0;
  occult_card: Card = SENTINEL_CARD;
  occult_lane: number = 0;
  listener: PlayerListener | null = null;

  constructor(
    seat: number,
    deck: Deck,
    lane0: Lane,
    lane1: Lane,
    lane2: Lane,
  ) {
    this.seat = seat;
    this.deck = deck;
    this.lane0 = lane0;
    this.lane1 = lane1;
    this.lane2 = lane2;
    this.hand = new Hand(deck, seat);
    this.hand.listener = this;
    // Player also subscribes to Deck broadcasts, for Occultist mid-effect
    // drew_hand / hand_empty routing.
    deck.listeners.push(this);
  }

  // --- Inlined observations (helpers, not state transitions). ---

  get tower_eligible(): Card[] {
    return this.hand.cards.filter(
      c => c.type === "hero" || c.type === "neutral",
    );
  }

  get top_hand(): Card {
    return this.hand.cards[0] ?? SENTINEL_CARD;
  }

  get top_tower_fodder(): Card {
    return this.tower_eligible[0] ?? SENTINEL_CARD;
  }

  get enemy_seat(): number {
    return 1 - this.seat;
  }

  lane(idx: number): Lane {
    if (idx === 0) return this.lane0;
    if (idx === 1) return this.lane1;
    return this.lane2;
  }

  others(idx: number): number[] {
    if (idx === 0) return [1, 2];
    if (idx === 1) return [0, 2];
    return [0, 1];
  }

  next_lane(idx: number): number {
    return (idx + 1) % 3;
  }

  get first_open_lane(): number {
    if (!this.lane0.side(this.seat).tower_placed) return 0;
    if (!this.lane1.side(this.seat).tower_placed) return 1;
    if (!this.lane2.side(this.seat).tower_placed) return 2;
    return -1;
  }

  // The lane the rolled card lands in — the rolled lane if this player has
  // a tower there, else first of 2,0,1 with one. -1 if none.
  first_deployable_lane(rolled: number): number {
    if (this.lane(rolled).side(this.seat).tower_placed) return rolled;
    if (this.lane2.side(this.seat).tower_placed) return 2;
    if (this.lane0.side(this.seat).tower_placed) return 0;
    if (this.lane1.side(this.seat).tower_placed) return 1;
    return -1;
  }

  // --- idle arms ---

  begin_draw(): void {
    if (this.phase !== "idle") return;
    this.hand.begin_draw();
  }

  // Hand -> Player bubble-up.
  on_hand_draw_done(): void {
    if (this.phase !== "idle") return;
    this.listener?.on_player_draw_done(this.seat);
  }

  win_target(card: Card): void {
    if (this.phase !== "idle") return;
    this.won = [...this.won, card];
    this.total_auction_wins += 1;
  }

  inc_towers_lost(): void {
    if (this.phase !== "idle") return;
    this.towers_lost += 1;
  }

  check_mulligan(round: number): void {
    if (this.phase !== "idle") return;
    const heroes = this.hand.cards.filter(c => c.type === "hero").length;
    if (round === 0 && heroes === 0) {
      this.mulligans += 1;
    }
  }

  // --- deploying ---

  begin_deploy_round(dl: number): void {
    if (this.phase !== "idle") return;
    this.phase = "deploying";
    this.try_place(dl);
  }

  private try_place(lane: number): void {
    if (this.phase !== "deploying") return;
    const top = this.top_hand;
    const have_card = this.hand.cards.length > 0;

    // Storm Spirit — cross-lane damage, lands on the rolled lane either way.
    if (have_card && top.on_deploy.kind === "cross_damage_all") {
      const card = top;
      for (const l of this.others(lane)) {
        this.lane(l).cross_damage(this.enemy_seat, card.on_deploy.amount);
      }
      this.lane(lane).place_hero(card, this.seat);
      this.record_and_next(card, lane);
      return;
    }
    // Blink Blade — pull the next-lane front onto this lane.
    if (
      have_card &&
      top.on_deploy.kind === "move_from_next_lane_front" &&
      this.lane(this.next_lane(lane)).side(this.seat).units.length > 0
    ) {
      const src = this.next_lane(lane);
      const unit = this.lane(src).side(this.seat).units[0];
      const card = top;
      this.lane(lane).place_hero(unit.card, this.seat);
      this.lane(src).remove_hero_by_name(this.seat, unit.card.name);
      this.record_and_next(card, lane);
      return;
    }
    // Occultist — deploy, then draw 2 and discard the weakest.
    if (
      have_card &&
      top.on_deploy.kind === "draw_n_discard_weakest" &&
      this.first_deployable_lane(lane) >= 0
    ) {
      const slot = this.first_deployable_lane(lane);
      const card = top;
      this.lane(slot).place_hero(card, this.seat);
      this.occult_card = card;
      this.occult_remaining = 2;
      this.occult_lane = lane;
      this.deck.try_draw_hand(this.seat);
      return;
    }
    // Vanilla deploy.
    if (have_card && this.first_deployable_lane(lane) >= 0) {
      const slot = this.first_deployable_lane(lane);
      const card = top;
      this.lane(slot).place_hero(card, this.seat);
      this.record_and_next(card, lane);
      return;
    }
    // Fall-through — empty hand or no eligible lane.
    this.phase = "idle";
    this.listener?.on_player_deploy_done(this.seat);
  }

  private record_and_next(card: Card, lane: number): void {
    if (this.phase !== "deploying") return;
    this.total_deploys += 1;
    this.hand.remove_by_name(card.name);
    this.listener?.on_player_card_deployed(card, this.seat);
    this.try_place(lane);
  }

  // DeckListener. Hand also subscribes, but its phase check drops these when
  // the Hand is idle (which it is throughout Player deploying).
  on_deck_drew_hand(card: Card, seat: number): void {
    if (this.phase !== "deploying") return;
    if (seat !== this.seat) return;

    if (this.occult_remaining > 1) {
      // Occultist mid-effect: append and pull another.
      this.hand.append(card);
      this.occult_remaining -= 1;
      this.deck.try_draw_hand(this.seat);
      return;
    }
    if (this.occult_remaining === 1) {
      // Occultist final draw: append, pick weakest across hand ∪ {card},
      // discard it, then finish the deploy as record_and_next would.
      const combined = [...this.hand.cards, card];
      const sorted = combined
        .slice()
        .sort((a, b) => a.coin + a.attack + a.hp - (b.coin + b.attack + b.hp));
      const weakest = sorted[0] ?? this.occult_card;
      this.hand.append(card);
      this.occult_remaining = 0;
      this.hand.remove_by_name(weakest.name);
      this.deck.add_discard(weakest);
      this.total_deploys += 1;
      this.hand.remove_by_name(this.occult_card.name);
      this.listener?.on_player_card_deployed(this.occult_card, this.seat);
      this.try_place(this.occult_lane);
      return;
    }
    // Non-Occultist drew_hand in deploying — silent drop.
  }

  on_deck_hand_empty(seat: number): void {
    if (this.phase !== "deploying") return;
    if (seat !== this.seat) return;
    if (this.occult_remaining <= 0) return;
    // Deck ran empty mid-Occultist — finish cleanly.
    this.occult_remaining = 0;
    this.total_deploys += 1;
    this.hand.remove_by_name(this.occult_card.name);
    this.listener?.on_player_card_deployed(this.occult_card, this.seat);
    this.try_place(this.occult_lane);
  }

  // --- placing_towers ---

  begin_tower_quota(round: number): void {
    if (this.phase !== "idle") return;
    this.phase = "placing_towers";
    this.try_place_tower(round);
  }

  private try_place_tower(round: number): void {
    if (this.phase !== "placing_towers") return;
    const quota = Math.min(round + 1, 3);
    if (
      this.towers_placed < quota &&
      this.tower_eligible.length > 0 &&
      this.first_open_lane >= 0
    ) {
      const hero = this.top_tower_fodder;
      this.lane(this.first_open_lane).place_tower(this.seat, hero);
      this.towers_placed += 1;
      this.hand.remove_by_name(hero.name);
      this.try_place_tower(round);
      return;
    }
    this.phase = "idle";
    this.listener?.on_player_tower_quota_done(this.seat);
  }

  // --- capping_hand ---

  begin_hand_cap(): void {
    if (this.phase !== "idle") return;
    this.phase = "capping_hand";
    this.try_cap();
  }

  private try_cap(): void {
    if (this.phase !== "capping_hand") return;
    if (this.hand.cards.length > 3) {
      const card = this.top_hand;
      this.listener?.on_player_hand_overflow_discard(card, this.seat);
      this.hand.remove_by_name(card.name);
      this.try_cap();
      return;
    }
    this.phase = "idle";
    this.listener?.on_player_hand_cap_done(this.seat);
  }
}

// ================================================================
// Market atom. The face-up auction row.
// ================================================================

type MarketPhase = "idle" | "refilling" | "end";

class Market implements DeckListener {
  phase: MarketPhase = "idle";
  cards: Card[] = [];
  deck: Deck;
  listener: MarketListener | null = null;

  constructor(deck: Deck) {
    this.deck = deck;
    deck.listeners.push(this);
  }

  begin_refill(): void {
    if (this.phase !== "idle") return;
    this.phase = "refilling";
    this.pull();
  }

  private pull(): void {
    if (this.phase !== "refilling") return;
    if (this.cards.length < 3) {
      this.deck.try_draw_market();
      return;
    }
    this.phase = "idle";
    this.listener?.on_market_refill_done();
  }

  on_deck_drew_market(card: Card): void {
    if (this.phase !== "refilling") return;
    this.cards = [...this.cards, card];
    this.pull();
  }

  on_deck_market_empty(): void {
    if (this.phase !== "refilling") return;
    this.phase = "idle";
    this.listener?.on_market_refill_done();
  }

  remove_by_name(name: string): void {
    this.cards = this.cards.filter(c => c.name !== name);
  }
}

// ================================================================
// Game atom (the cxc `def main:`). Owns the whole world, drives round
// progression through its own phase machine.
// ================================================================

type GamePhase =
  | "idle"
  | "dealing"
  | "refilling"
  | "auctioning"
  | "bidding"
  | "deploying"
  | "placing_towers"
  | "capping_hand"
  | "combatting"
  | "resolving_round"
  | "end";

class Game implements PlayerListener, MarketListener, LaneListener {
  phase: GamePhase = "idle";
  prng: SeededPrng = new SeededPrng(42);
  deck: Deck;
  lane0: Lane;
  lane1: Lane;
  lane2: Lane;
  p0: Player;
  p1: Player;
  market: Market;
  lanes: Lane[];
  players: Player[];
  initial_deck: Card[];

  combat_dones: number = 0;
  deploy_counts: Map<string, number> = new Map();
  auction_wins: Map<string, number> = new Map();
  round: number = 0;
  auctions_run: number = 0;
  auction_pass_streak: number = 0;

  constructor() {
    this.deck = new Deck(this.prng);
    this.lane0 = new Lane(0);
    this.lane1 = new Lane(1);
    this.lane2 = new Lane(2);
    this.p0 = new Player(0, this.deck, this.lane0, this.lane1, this.lane2);
    this.p1 = new Player(1, this.deck, this.lane0, this.lane1, this.lane2);
    this.market = new Market(this.deck);
    this.lanes = [this.lane0, this.lane1, this.lane2];
    this.players = [this.p0, this.p1];
    this.initial_deck = Game.build_initial_deck();

    this.p0.listener = this;
    this.p1.listener = this;
    this.market.listener = this;
    this.lane0.listener = this;
    this.lane1.listener = this;
    this.lane2.listener = this;
  }

  at_seat(seat: number): Player {
    return seat === 0 ? this.p0 : this.p1;
  }

  // -1 for a draw when both lose a second tower in the same round or the
  // round cap comes first; else the winning seat.
  get game_winner(): number {
    if (this.p0.towers_lost >= 2 && this.p1.towers_lost >= 2) return -1;
    if (this.p0.towers_lost >= 2) return 1;
    if (this.p1.towers_lost >= 2) return 0;
    return -1;
  }

  lane_tw_str(l: Lane): string {
    return `${l.side_0.tower_hp}/${l.side_1.tower_hp}`;
  }

  lane_names_str(l: Lane): string {
    const n0 = l.side_0.tower_card.name;
    const n1 = l.side_1.tower_card.name;
    return `${n0}/${n1}`;
  }

  // --- Lifecycle entry: `-> idle then: ...` + `idle <: kickoff`. ---

  run(): void {
    this.deck.seed(this.initial_deck);
    this.kickoff();
  }

  private kickoff(): void {
    if (this.phase !== "idle") return;
    this.phase = "dealing";
    this.p0.begin_draw();
  }

  // --- dealing / refilling ---

  on_player_draw_done(seat: number): void {
    if (this.phase !== "dealing") return;
    if (seat === 0) {
      this.p1.begin_draw();
      return;
    }
    // seat === 1
    this.p0.check_mulligan(this.round);
    this.p1.check_mulligan(this.round);
    this.phase = "refilling";
    this.market.begin_refill();
  }

  on_market_refill_done(): void {
    if (this.phase !== "refilling") return;
    this.phase = "auctioning";
    this.next_auction();
  }

  // --- auctioning / bidding ---

  private next_auction(): void {
    if (this.phase !== "auctioning") return;
    if (
      this.market.cards.length > 0 &&
      this.auctions_run < 8 &&
      this.auction_pass_streak < 2
    ) {
      const card = this.market.cards[0] ?? SENTINEL_CARD;
      const p0_bid =
        this.prng.below(2) === 0 ? 0 : this.p0.hand.cards.length;
      const p1_bid =
        this.prng.below(2) === 0 ? 0 : this.p1.hand.cards.length;
      this.market.remove_by_name(card.name);
      let winner: number;
      if (p0_bid >= card.coin && p0_bid >= p1_bid) winner = 0;
      else if (p1_bid >= card.coin && p1_bid > p0_bid) winner = 1;
      else winner = -1;
      this.phase = "bidding";
      this.settle(card, winner);
      return;
    }
    // Row empty, auction cap hit, or two passes — move on to deploy.
    this.phase = "deploying";
    this.p0.begin_deploy_round(this.prng.below(3));
  }

  private settle(card: Card, winner: number): void {
    if (this.phase !== "bidding") return;
    if (winner >= 0) {
      this.at_seat(winner).win_target(card);
      this.auctions_run += 1;
      this.auction_pass_streak = 0;
      this.auction_wins.set(
        card.name,
        (this.auction_wins.get(card.name) ?? 0) + 1,
      );
    } else {
      this.auctions_run += 1;
      this.auction_pass_streak += 1;
    }
    this.phase = "auctioning";
    this.next_auction();
  }

  // --- deploying / placing_towers / capping_hand ---

  on_player_deploy_done(seat: number): void {
    if (this.phase !== "deploying") return;
    if (seat === 0) {
      this.p1.begin_deploy_round(this.prng.below(3));
      return;
    }
    this.phase = "placing_towers";
    this.p0.begin_tower_quota(this.round);
  }

  on_player_card_deployed(card: Card, _seat: number): void {
    this.deploy_counts.set(
      card.name,
      (this.deploy_counts.get(card.name) ?? 0) + 1,
    );
  }

  on_player_tower_quota_done(seat: number): void {
    if (this.phase !== "placing_towers") return;
    if (seat === 0) {
      this.p1.begin_tower_quota(this.round);
      return;
    }
    this.phase = "capping_hand";
    this.p0.begin_hand_cap();
  }

  on_player_hand_cap_done(seat: number): void {
    if (this.phase !== "capping_hand") return;
    if (seat === 0) {
      this.p1.begin_hand_cap();
      return;
    }
    this.phase = "combatting";
    this.resolve_combat();
  }

  on_player_hand_overflow_discard(card: Card, _seat: number): void {
    this.deck.add_discard(card);
  }

  // --- Lane -> Game broadcasts (any phase). ---

  on_lane_tower_lost(seat: number, card: Card): void {
    // During combat the Players are idle, so inc_towers_lost's phase guard
    // passes. The lost tower's card returns to the owner's hand.
    this.at_seat(seat).inc_towers_lost();
    this.at_seat(seat).hand.append(card);
  }

  on_lane_unit_gone(card: Card, seat: number, hp: number): void {
    if (hp === 0) {
      this.deck.add_discard(card);
    } else {
      this.at_seat(seat).hand.append(card);
    }
  }

  // --- combat / resolving_round ---

  private resolve_combat(): void {
    if (this.phase !== "combatting") return;
    console.log(
      `-- combat -- l0 tw=${this.lane_tw_str(this.lane0)} l1 tw=${this.lane_tw_str(this.lane1)} l2 tw=${this.lane_tw_str(this.lane2)}`,
    );
    for (const l of this.lanes) {
      const idx = l.idx.toString();
      for (const u of l.side_0.units) {
        console.log(`  l${idx} t0: ${u.card.name} hp=${u.current_hp}`);
      }
      for (const u of l.side_1.units) {
        console.log(`  l${idx} t1: ${u.card.name} hp=${u.current_hp}`);
      }
    }
    this.combat_dones = 0;
    for (const l of this.lanes) l.begin_combat();
  }

  on_lane_combat_done(): void {
    if (this.phase !== "combatting") return;
    if (this.combat_dones + 1 < this.lanes.length) {
      this.combat_dones += 1;
      return;
    }
    this.phase = "resolving_round";
    this.end_round();
  }

  private end_round(): void {
    if (this.phase !== "resolving_round") return;
    const game_over =
      this.p0.towers_lost >= 2 ||
      this.p1.towers_lost >= 2 ||
      this.round >= 11;
    if (game_over) {
      this.phase = "end";
      this.print_final();
      return;
    }
    this.round += 1;
    this.auctions_run = 0;
    this.auction_pass_streak = 0;
    this.phase = "dealing";
    this.p0.begin_draw();
  }

  // --- `-> end then: ...` ---

  private print_final(): void {
    for (const p of this.players) {
      const name = `p${p.seat}`;
      console.log(`${name} hand:`);
      for (const c of p.hand.cards) console.log(c.name);
      console.log(`${name} won:`);
      for (const c of p.won) console.log(c.name);
    }
    console.log("market:");
    for (const c of this.market.cards) console.log(c.name);
    console.log("discard:");
    for (const c of this.deck.discard) console.log(c.name);
    console.log("deck:");
    for (const c of this.deck.cards) console.log(c.name);
    for (const l of this.lanes) {
      console.log(`lane${l.idx} t0/t1:`);
      for (const u of l.side_0.units) console.log(u.card.name);
      for (const u of l.side_1.units) console.log(u.card.name);
    }
    console.log(
      `towers l0 ${this.lane_tw_str(this.lane0)} l1 ${this.lane_tw_str(this.lane1)} l2 ${this.lane_tw_str(this.lane2)}`,
    );
    console.log(
      `tower cards: l0[${this.lane_names_str(this.lane0)}] l1[${this.lane_names_str(this.lane1)}] l2[${this.lane_names_str(this.lane2)}]`,
    );
    console.log("");
    console.log("=== game result (Python-parity output) ===");
    console.log(
      `winner: ${this.game_winner}, rounds: ${this.round + 1}`,
    );
    // Python counts towers by the attacker — each player's figure is the
    // other player's towers_lost.
    console.log(
      `towers_destroyed p0: ${this.p1.towers_lost}, p1: ${this.p0.towers_lost}`,
    );
    console.log(
      `deploy_counts (total p0=${this.p0.total_deploys} p1=${this.p1.total_deploys}):`,
    );
    for (const c of this.initial_deck) {
      const label = (c.name + ":").padEnd(18, " ");
      console.log(`  ${label}${this.deploy_counts.get(c.name) ?? 0}`);
    }
    console.log(
      `auction_wins (total p0=${this.p0.total_auction_wins} p1=${this.p1.total_auction_wins}):`,
    );
    for (const c of this.initial_deck) {
      const label = (c.name + ":").padEnd(18, " ");
      console.log(`  ${label}${this.auction_wins.get(c.name) ?? 0}`);
    }
    console.log(`mulligans: [${this.p0.mulligans}, ${this.p1.mulligans}]`);
  }

  // --- Static deck constructor (`def main`'s initial_deck literal). ---

  private static build_initial_deck(): Card[] {
    const no_effect: DeployEffect = {
      kind: "none",
      scope: "own_side",
      amount: 0,
    };
    const dmg_enemy_2: DeployEffect = {
      kind: "damage_all",
      scope: "enemy_side",
      amount: 2,
    };
    const dmg_enemy_5: DeployEffect = {
      kind: "damage_all",
      scope: "enemy_side",
      amount: 5,
    };
    const heal_own_2: DeployEffect = {
      kind: "heal_all",
      scope: "own_side",
      amount: 2,
    };
    const silence_enemy: DeployEffect = {
      kind: "silence_all",
      scope: "enemy_side",
      amount: 0,
    };
    const buff_own_1: DeployEffect = {
      kind: "buff_all",
      scope: "own_side",
      amount: 1,
    };
    const clear_enemy: DeployEffect = {
      kind: "clear_all",
      scope: "enemy_side",
      amount: 0,
    };
    const thorn_own: DeployEffect = {
      kind: "attach_thorn_all",
      scope: "own_side",
      amount: 0,
    };
    const cross_dmg_2: DeployEffect = {
      kind: "cross_damage_all",
      scope: "enemy_side",
      amount: 2,
    };
    const move_next_lane: DeployEffect = {
      kind: "move_from_next_lane_front",
      scope: "own_side",
      amount: 0,
    };
    const draw_2_discard_1: DeployEffect = {
      kind: "draw_n_discard_weakest",
      scope: "own_side",
      amount: 2,
    };
    const no_combat: CombatEffect = { kind: "none", amount: 0 };
    const bypass: CombatEffect = { kind: "attack_bypass", amount: 0 };
    const dmg_cap_2: CombatEffect = { kind: "damage_cap", amount: 2 };
    const reflect_1: CombatEffect = { kind: "reflect_bonus", amount: 1 };
    const tower_plus_2: CombatEffect = { kind: "tower_bonus", amount: 2 };

    return [
      { name: "Blood Demon",      type: "hero",    attack: 5, hp: 4, coin: 3, pre_deploy: no_effect,   on_deploy: dmg_enemy_2,      combat_effect: no_combat },
      { name: "Storm Spirit",     type: "hero",    attack: 5, hp: 3, coin: 3, pre_deploy: no_effect,   on_deploy: cross_dmg_2,      combat_effect: no_combat },
      { name: "Marksman",         type: "hero",    attack: 4, hp: 4, coin: 4, pre_deploy: no_effect,   on_deploy: no_effect,        combat_effect: bypass },
      { name: "Axe King",         type: "hero",    attack: 4, hp: 8, coin: 4, pre_deploy: no_effect,   on_deploy: no_effect,        combat_effect: no_combat },
      { name: "Tide Beast",       type: "hero",    attack: 3, hp: 9, coin: 5, pre_deploy: no_effect,   on_deploy: silence_enemy,    combat_effect: no_combat },
      { name: "Fallen Warlock",   type: "hero",    attack: 4, hp: 5, coin: 5, pre_deploy: clear_enemy, on_deploy: no_effect,        combat_effect: no_combat },
      { name: "Occultist",        type: "hero",    attack: 3, hp: 4, coin: 4, pre_deploy: no_effect,   on_deploy: draw_2_discard_1, combat_effect: no_combat },
      { name: "Faerie Dragon",    type: "hero",    attack: 3, hp: 4, coin: 3, pre_deploy: no_effect,   on_deploy: no_effect,        combat_effect: dmg_cap_2 },
      { name: "Mage Slayer",      type: "hero",    attack: 6, hp: 6, coin: 5, pre_deploy: no_effect,   on_deploy: dmg_enemy_5,      combat_effect: no_combat },
      { name: "Phantom Blade",    type: "hero",    attack: 8, hp: 6, coin: 6, pre_deploy: no_effect,   on_deploy: no_effect,        combat_effect: tower_plus_2 },
      { name: "Kobold Boss",      type: "neutral", attack: 1, hp: 2, coin: 2, pre_deploy: no_effect,   on_deploy: buff_own_1,       combat_effect: no_combat },
      { name: "Wolf Leader",      type: "neutral", attack: 3, hp: 2, coin: 3, pre_deploy: no_effect,   on_deploy: buff_own_1,       combat_effect: no_combat },
      { name: "Satyr Torturer",   type: "neutral", attack: 2, hp: 3, coin: 2, pre_deploy: no_effect,   on_deploy: heal_own_2,       combat_effect: no_combat },
      { name: "Centaur Champion", type: "neutral", attack: 2, hp: 5, coin: 3, pre_deploy: no_effect,   on_deploy: no_effect,        combat_effect: reflect_1 },
      { name: "Hell Bear",        type: "neutral", attack: 4, hp: 3, coin: 3, pre_deploy: no_effect,   on_deploy: dmg_enemy_2,      combat_effect: no_combat },
      { name: "Blink Blade",      type: "item",    attack: 0, hp: 0, coin: 3, pre_deploy: no_effect,   on_deploy: move_next_lane,   combat_effect: no_combat },
      { name: "Thorn Armor",      type: "item",    attack: 0, hp: 0, coin: 3, pre_deploy: no_effect,   on_deploy: thorn_own,        combat_effect: no_combat },
      { name: "Return Scroll",    type: "scroll",  attack: 0, hp: 0, coin: 3, pre_deploy: no_effect,   on_deploy: heal_own_2,       combat_effect: no_combat },
    ];
  }
}

// ================================================================
// Entry point.
// ================================================================

function main(): void {
  const game = new Game();
  game.run();
}

main();

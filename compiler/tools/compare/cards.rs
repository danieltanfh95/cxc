// Pocket MOBA, ported from cards.cxc (Corvid actor model) to idiomatic Rust.
// Structural port: each cxc atom becomes a struct + phase enum, each transition
// arm becomes a method on Game. Phase names and message names are preserved so
// the trace shape stays comparable to the cxc trace.
//
// RNG is a simple SplitMix64 (not CPython MT19937), so this port does NOT match
// the Python reference byte-for-byte. Byte parity isn't the point here — the
// comparison target is lifecycle structure.

use std::collections::HashMap;

// ============================================================
// Value types (records / enums from the cxc source)
// ============================================================

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CardType {
    Hero,
    Neutral,
    Item,
    Scroll,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum EffectKind {
    None,
    DamageAll,
    HealAll,
    SilenceAll,
    BuffAll,
    ClearAll,
    AttachThornAll,
    CrossDamageAll,
    MoveFromNextLaneFront,
    DrawNDiscardWeakest,
    DamageCap,
    ReflectBonus,
    AttackBypass,
    TowerBonus,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum EffectScope {
    OwnSide,
    EnemySide,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct DeployEffect {
    pub(crate) kind: EffectKind,
    pub(crate) scope: EffectScope,
    pub(crate) amount: i64,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CombatEffect {
    pub(crate) kind: EffectKind,
    pub(crate) amount: i64,
}

#[derive(Clone, Debug)]
pub(crate) struct Card {
    pub(crate) name: String,
    pub(crate) ty: CardType,
    pub(crate) attack: i64,
    pub(crate) hp: i64,
    pub(crate) coin: i64,
    pub(crate) pre_deploy: DeployEffect,
    pub(crate) on_deploy: DeployEffect,
    pub(crate) combat_effect: CombatEffect,
}

const NO_EFFECT: DeployEffect = DeployEffect { kind: EffectKind::None, scope: EffectScope::OwnSide, amount: 0 };
const NO_COMBAT: CombatEffect = CombatEffect { kind: EffectKind::None, amount: 0 };

pub(crate) fn sentinel_card() -> Card {
    Card {
        name: "?".to_string(),
        ty: CardType::Hero,
        attack: 0,
        hp: 0,
        coin: 0,
        pre_deploy: NO_EFFECT,
        on_deploy: NO_EFFECT,
        combat_effect: NO_COMBAT,
    }
}

// ============================================================
// Phase enums (one per cxc atom)
// ============================================================

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum HandPhase { Idle, Drawing, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PlayerPhase { Idle, Deploying, PlacingTowers, CappingHand, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PlayableUnitPhase { Alive, Dead, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LaneSidePhase { Idle, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LanePhase {
    Idle, Preparing, Combatting, Unopposed, Destroying, Finalizing, End,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MarketPhase { Idle, Refilling, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DeckPhase { Idle, End }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum GamePhase {
    Idle, Dealing, Refilling, Auctioning, Bidding, Deploying,
    PlacingTowers, CappingHand, Combatting, ResolvingRound, End,
}

// ============================================================
// Prng: SplitMix64. Matches `prng.below(n)` surface, not Python's MT19937.
// ============================================================

pub(crate) struct SeededPrng { state: u64 }

impl SeededPrng {
    pub(crate) fn new(seed: u64) -> Self {
        Self { state: seed }
    }
    pub(crate) fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^ (z >> 31)
    }
    pub(crate) fn below(&mut self, n: i64) -> i64 {
        if n <= 0 { return 0; }
        (self.next_u64() % (n as u64)) as i64
    }
}

// ============================================================
// Atom structs (data only; transitions live as methods on Game).
// ============================================================

pub(crate) struct Hand {
    pub(crate) phase: HandPhase,
    pub(crate) owner_seat: i64,
    pub(crate) cards: Vec<Card>,
}

pub(crate) struct PlayableUnit {
    pub(crate) phase: PlayableUnitPhase,
    pub(crate) card: Card,
    pub(crate) owner_seat: i64,
    pub(crate) current_hp: i64,
    pub(crate) silenced: bool,
    pub(crate) thorn_attached: bool,
    pub(crate) atk_bonus: i64,
}

impl PlayableUnit {
    pub(crate) fn new(card: Card, owner_seat: i64) -> Self {
        let hp = card.hp;
        Self {
            phase: PlayableUnitPhase::Alive,
            card,
            owner_seat,
            current_hp: hp,
            silenced: false,
            thorn_attached: false,
            atk_bonus: 0,
        }
    }
    pub(crate) fn total_attack(&self) -> i64 { self.card.attack + self.atk_bonus }
    pub(crate) fn reflect_bonus(&self) -> i64 {
        if self.card.combat_effect.kind == EffectKind::ReflectBonus {
            self.card.combat_effect.amount
        } else if self.thorn_attached { 1 } else { 0 }
    }
    pub(crate) fn effective_damage(&self, amount: i64) -> i64 {
        if self.card.combat_effect.kind == EffectKind::DamageCap {
            amount.min(self.card.combat_effect.amount)
        } else { amount }
    }
    // alive <: take_damage
    pub(crate) fn take_damage(&mut self, amount: i64) {
        if self.phase != PlayableUnitPhase::Alive { return; } // dead <: * -> dead
        let eff = self.effective_damage(amount);
        if eff < self.current_hp {
            self.current_hp -= eff;
        } else {
            self.current_hp = 0;
            self.phase = PlayableUnitPhase::Dead;
        }
    }
    pub(crate) fn heal(&mut self, amount: i64) {
        if self.phase != PlayableUnitPhase::Alive { return; }
        self.current_hp = (self.current_hp + amount).min(self.card.hp);
    }
    pub(crate) fn silence(&mut self) {
        if self.phase != PlayableUnitPhase::Alive { return; }
        self.silenced = true;
    }
    pub(crate) fn buff_atk(&mut self, amount: i64) {
        if self.phase != PlayableUnitPhase::Alive { return; }
        self.atk_bonus += amount;
    }
    pub(crate) fn attach_thorn(&mut self) {
        if self.phase != PlayableUnitPhase::Alive { return; }
        self.thorn_attached = true;
    }
}

pub(crate) struct LaneSide {
    pub(crate) phase: LaneSidePhase,
    pub(crate) team: i64,
    pub(crate) units: Vec<PlayableUnit>,
    pub(crate) tower_placed: bool,
    pub(crate) tower_card: Card,
    pub(crate) tower_hp: i64,
}

impl LaneSide {
    pub(crate) fn new(team: i64) -> Self {
        Self {
            phase: LaneSidePhase::Idle,
            team,
            units: Vec::new(),
            tower_placed: false,
            tower_card: sentinel_card(),
            tower_hp: 0,
        }
    }
    pub(crate) fn append(&mut self, card: Card) {
        let unit = PlayableUnit::new(card, self.team);
        self.units.push(unit);
    }
    pub(crate) fn damage_all(&mut self, amount: i64) {
        for u in self.units.iter_mut() { u.take_damage(amount); }
    }
    pub(crate) fn heal_all(&mut self, amount: i64) {
        for u in self.units.iter_mut() { u.heal(amount); }
    }
    pub(crate) fn silence_all(&mut self) {
        for u in self.units.iter_mut() { u.silence(); }
    }
    pub(crate) fn attach_thorn_all(&mut self) {
        for u in self.units.iter_mut() { u.attach_thorn(); }
    }
    pub(crate) fn buff_all(&mut self, amount: i64) {
        for u in self.units.iter_mut() { u.buff_atk(amount); }
    }
    pub(crate) fn remove_by_name(&mut self, name: &str) {
        self.units.retain(|u| u.card.name != name);
    }
    pub(crate) fn filter_dead(&mut self) {
        self.units.retain(|u| u.current_hp > 0);
    }
    pub(crate) fn clear_units(&mut self) {
        self.units.clear();
    }
    pub(crate) fn place_tower(&mut self, card: Card) {
        if !self.tower_placed {
            self.tower_placed = true;
            self.tower_card = card;
            self.tower_hp = 5;
        }
    }
    pub(crate) fn clear_tower(&mut self) {
        self.tower_placed = false;
        self.tower_hp = 0;
    }
    pub(crate) fn damage_tower(&mut self, amount: i64) {
        self.tower_hp = (self.tower_hp - amount).max(0);
    }
    pub(crate) fn absorb_overkill_if_unopposed(&mut self, overkill: i64) {
        if self.tower_placed && self.units.iter().filter(|u| u.current_hp > 0).count() == 0 {
            self.tower_hp = (self.tower_hp - overkill).max(0);
        }
    }
    pub(crate) fn living_count(&self) -> usize {
        self.units.iter().filter(|u| u.current_hp > 0).count()
    }
}

pub(crate) struct Lane {
    pub(crate) phase: LanePhase,
    pub(crate) idx: i64,
    pub(crate) side_0: LaneSide,
    pub(crate) side_1: LaneSide,
    pub(crate) combat_idx: i64,
    pub(crate) overkill_0: i64,
    pub(crate) overkill_1: i64,
}

impl Lane {
    pub(crate) fn new(idx: i64) -> Self {
        Self {
            phase: LanePhase::Idle,
            idx,
            side_0: LaneSide::new(0),
            side_1: LaneSide::new(1),
            combat_idx: 0,
            overkill_0: 0,
            overkill_1: 0,
        }
    }
    pub(crate) fn side(&self, team: i64) -> &LaneSide {
        if team == 0 { &self.side_0 } else { &self.side_1 }
    }
    pub(crate) fn side_mut(&mut self, team: i64) -> &mut LaneSide {
        if team == 0 { &mut self.side_0 } else { &mut self.side_1 }
    }
    pub(crate) fn enemy_side_mut(&mut self, team: i64) -> &mut LaneSide {
        if team == 0 { &mut self.side_1 } else { &mut self.side_0 }
    }
    pub(crate) fn paired(&self) -> bool {
        let ci = self.combat_idx as usize;
        ci < self.side_0.units.len() && ci < self.side_1.units.len()
    }
    pub(crate) fn tower_lost_seat(&self) -> i64 {
        if self.side_0.tower_placed && self.side_0.tower_hp == 0 { 0 }
        else if self.side_1.tower_placed && self.side_1.tower_hp == 0 { 1 }
        else { -1 }
    }
    pub(crate) fn front_total_attack(&self, team: i64) -> i64 {
        let s = self.side(team);
        s.units[self.combat_idx as usize].total_attack()
    }
    pub(crate) fn tower_damage(&self, team: i64) -> i64 {
        let u = &self.side(team).units[self.combat_idx as usize];
        let bonus = if u.card.combat_effect.kind == EffectKind::TowerBonus { u.card.combat_effect.amount } else { 0 };
        u.total_attack() + bonus
    }
}

pub(crate) struct Player {
    pub(crate) phase: PlayerPhase,
    pub(crate) seat: i64,
    pub(crate) hand: Hand,
    pub(crate) won: Vec<Card>,
    pub(crate) mulligans: i64,
    pub(crate) total_deploys: i64,
    pub(crate) total_auction_wins: i64,
    pub(crate) towers_placed: i64,
    pub(crate) towers_lost: i64,
    pub(crate) occult_remaining: i64,
    pub(crate) occult_card: Card,
    pub(crate) occult_lane: i64,
}

impl Player {
    pub(crate) fn new(seat: i64) -> Self {
        Self {
            phase: PlayerPhase::Idle,
            seat,
            hand: Hand { phase: HandPhase::Idle, owner_seat: seat, cards: Vec::new() },
            won: Vec::new(),
            mulligans: 0,
            total_deploys: 0,
            total_auction_wins: 0,
            towers_placed: 0,
            towers_lost: 0,
            occult_remaining: 0,
            occult_card: sentinel_card(),
            occult_lane: 0,
        }
    }
}

pub(crate) struct Market {
    pub(crate) phase: MarketPhase,
    pub(crate) cards: Vec<Card>,
}

pub(crate) struct Deck {
    pub(crate) phase: DeckPhase,
    pub(crate) cards: Vec<Card>,
    pub(crate) discard: Vec<Card>,
    pub(crate) prng: SeededPrng,
}

// ============================================================
// Game: owns every atom and drives dispatch. In cxc, `|>` is a direct call
// and `:>` emits an upward / self event. The Rust translation collapses both
// into synchronous method calls that mutate shared state.
// ============================================================

pub(crate) struct Game {
    pub(crate) phase: GamePhase,
    pub(crate) deck: Deck,
    pub(crate) lanes: [Lane; 3],
    pub(crate) players: [Player; 2],
    pub(crate) market: Market,
    pub(crate) round: i64,
    pub(crate) auctions_run: i64,
    pub(crate) auction_pass_streak: i64,
    pub(crate) combat_dones: i64,
    pub(crate) deploy_counts: HashMap<String, i64>,
    pub(crate) auction_wins: HashMap<String, i64>,
    pub(crate) initial_deck: Vec<Card>,
}

impl Game {
    pub(crate) fn new(seed: u64, initial_deck: Vec<Card>) -> Self {
        Self {
            phase: GamePhase::Idle,
            deck: Deck {
                phase: DeckPhase::Idle,
                cards: Vec::new(),
                discard: Vec::new(),
                prng: SeededPrng::new(seed),
            },
            lanes: [Lane::new(0), Lane::new(1), Lane::new(2)],
            players: [Player::new(0), Player::new(1)],
            market: Market { phase: MarketPhase::Idle, cards: Vec::new() },
            round: 0,
            auctions_run: 0,
            auction_pass_streak: 0,
            combat_dones: 0,
            deploy_counts: HashMap::new(),
            auction_wins: HashMap::new(),
            initial_deck,
        }
    }

    // Helpers mirroring Player observations.
    pub(crate) fn enemy_seat(seat: i64) -> i64 { 1 - seat }
    pub(crate) fn next_lane(idx: i64) -> i64 { (idx + 1) % 3 }
    pub(crate) fn others_of(idx: i64) -> [i64; 2] {
        match idx { 0 => [1, 2], 1 => [0, 2], _ => [0, 1] }
    }
    pub(crate) fn at_seat_mut(&mut self, seat: i64) -> &mut Player {
        &mut self.players[seat as usize]
    }

    // Player first_open_lane: first lane where this seat has no tower.
    pub(crate) fn first_open_lane(&self, seat: i64) -> i64 {
        for i in 0..3i64 {
            if !self.lanes[i as usize].side(seat).tower_placed { return i; }
        }
        -1
    }
    // Player first_deployable_lane(rolled): rolled if tower there, else 2,0,1.
    pub(crate) fn first_deployable_lane(&self, seat: i64, rolled: i64) -> i64 {
        if self.lanes[rolled as usize].side(seat).tower_placed { return rolled; }
        if self.lanes[2].side(seat).tower_placed { return 2; }
        if self.lanes[0].side(seat).tower_placed { return 0; }
        if self.lanes[1].side(seat).tower_placed { return 1; }
        -1
    }
    pub(crate) fn top_hand(&self, p: usize) -> Card {
        self.players[p].hand.cards.get(0).cloned().unwrap_or_else(sentinel_card)
    }
    pub(crate) fn tower_eligible(&self, p: usize) -> Vec<Card> {
        self.players[p].hand.cards.iter()
            .filter(|c| c.ty == CardType::Hero || c.ty == CardType::Neutral)
            .cloned()
            .collect()
    }

    // ======================================================
    // Deck arms
    // ======================================================
    pub(crate) fn deck_seed(&mut self, initial: Vec<Card>) {
        self.deck.cards = initial;
    }
    pub(crate) fn deck_add_discard(&mut self, card: Card) {
        self.deck.discard.push(card);
    }
    pub(crate) fn deck_try_draw_hand(&mut self, seat: i64) {
        if self.deck.cards.is_empty() && !self.deck.discard.is_empty() {
            self.deck.cards = std::mem::take(&mut self.deck.discard);
            self.deck_try_draw_hand(seat);
            return;
        }
        if self.deck.cards.is_empty() {
            self.emit_hand_empty(seat);
            return;
        }
        let n = self.deck.cards.len() as i64;
        let idx = self.deck.prng.below(n) as usize;
        let card = self.deck.cards[idx].clone();
        let name = card.name.clone();
        self.deck.cards.retain(|c| c.name != name);
        self.emit_drew_hand(card, seat);
    }
    pub(crate) fn deck_try_draw_market(&mut self) {
        if self.deck.cards.is_empty() && !self.deck.discard.is_empty() {
            self.deck.cards = std::mem::take(&mut self.deck.discard);
            self.deck_try_draw_market();
            return;
        }
        if self.deck.cards.is_empty() {
            self.emit_market_empty();
            return;
        }
        let n = self.deck.cards.len() as i64;
        let idx = self.deck.prng.below(n) as usize;
        let card = self.deck.cards[idx].clone();
        let name = card.name.clone();
        self.deck.cards.retain(|c| c.name != name);
        self.emit_drew_market(card);
    }

    // Deck -> hand / player routing.
    fn emit_drew_hand(&mut self, card: Card, seat: i64) {
        let p = seat as usize;
        // Player's occult arm takes precedence when deploying + remaining > 0.
        if self.players[p].phase == PlayerPhase::Deploying && self.players[p].occult_remaining > 1 {
            self.player_deploying_drew_hand_mid(p, card);
            return;
        }
        if self.players[p].phase == PlayerPhase::Deploying && self.players[p].occult_remaining == 1 {
            self.player_deploying_drew_hand_final(p, card);
            return;
        }
        // Otherwise, the hand's drawing arm consumes it.
        if self.players[p].hand.phase == HandPhase::Drawing {
            self.players[p].hand.cards.push(card);
            self.hand_pull(p);
        }
    }
    fn emit_hand_empty(&mut self, seat: i64) {
        let p = seat as usize;
        if self.players[p].phase == PlayerPhase::Deploying && self.players[p].occult_remaining > 0 {
            self.player_deploying_hand_empty(p);
            return;
        }
        if self.players[p].hand.phase == HandPhase::Drawing {
            self.players[p].hand.phase = HandPhase::Idle;
            self.player_hand_draw_done(p);
        }
    }
    fn emit_drew_market(&mut self, card: Card) {
        if self.market.phase == MarketPhase::Refilling {
            self.market.cards.push(card);
            self.market_pull();
        }
    }
    fn emit_market_empty(&mut self) {
        if self.market.phase == MarketPhase::Refilling {
            self.market.phase = MarketPhase::Idle;
            self.game_market_refill_done();
        }
    }

    // ======================================================
    // Hand arms
    // ======================================================
    pub(crate) fn hand_begin_draw(&mut self, p: usize) {
        self.players[p].hand.phase = HandPhase::Drawing;
        self.hand_pull(p);
    }
    pub(crate) fn hand_pull(&mut self, p: usize) {
        if self.players[p].hand.cards.len() < 3 {
            let seat = self.players[p].hand.owner_seat;
            self.deck_try_draw_hand(seat);
        } else {
            self.players[p].hand.phase = HandPhase::Idle;
            self.player_hand_draw_done(p);
        }
    }
    pub(crate) fn hand_append(&mut self, p: usize, card: Card) {
        self.players[p].hand.cards.push(card);
    }
    pub(crate) fn hand_remove_by_name(&mut self, p: usize, name: &str) {
        self.players[p].hand.cards.retain(|c| c.name != name);
    }

    // ======================================================
    // Player arms
    // ======================================================
    pub(crate) fn player_begin_draw(&mut self, p: usize) {
        // idle <: begin_draw
        self.hand_begin_draw(p);
    }
    pub(crate) fn player_hand_draw_done(&mut self, p: usize) {
        // emit :> p_draw_done up to Game
        self.game_p_draw_done(p);
    }
    pub(crate) fn player_win_target(&mut self, p: usize, card: Card) {
        self.players[p].won.push(card);
        self.players[p].total_auction_wins += 1;
    }
    pub(crate) fn player_inc_towers_lost(&mut self, p: usize) {
        self.players[p].towers_lost += 1;
    }
    pub(crate) fn player_check_mulligan(&mut self, p: usize, round: i64) {
        if round != 0 { return; }
        let heroes = self.players[p].hand.cards.iter()
            .filter(|c| c.ty == CardType::Hero).count();
        if heroes == 0 { self.players[p].mulligans += 1; }
    }
    pub(crate) fn player_begin_deploy_round(&mut self, p: usize, dl: i64) {
        self.players[p].phase = PlayerPhase::Deploying;
        self.player_try_place(p, dl);
    }
    pub(crate) fn player_try_place(&mut self, p: usize, lane: i64) {
        let seat = self.players[p].seat;
        if self.players[p].hand.cards.is_empty() {
            // Fall through to deploy_done
            self.players[p].phase = PlayerPhase::Idle;
            self.game_deploy_done(p);
            return;
        }
        let card = self.top_hand(p);

        // Storm Spirit: cross_damage_all.
        if card.on_deploy.kind == EffectKind::CrossDamageAll {
            let enemy = Self::enemy_seat(seat);
            let amount = card.on_deploy.amount;
            for other in Self::others_of(lane) {
                self.lane_cross_damage(other as usize, enemy, amount);
            }
            self.lane_place_hero(lane as usize, card.clone(), seat);
            self.player_record_and_next(p, card, lane);
            return;
        }

        // Blink Blade: move_from_next_lane_front.
        if card.on_deploy.kind == EffectKind::MoveFromNextLaneFront {
            let src = Self::next_lane(lane);
            if !self.lanes[src as usize].side(seat).units.is_empty() {
                let unit_card = self.lanes[src as usize].side(seat).units[0].card.clone();
                let unit_name = unit_card.name.clone();
                self.lane_place_hero(lane as usize, unit_card, seat);
                self.lane_remove_hero_by_name(src as usize, seat, &unit_name);
                self.player_record_and_next(p, card, lane);
                return;
            }
        }

        // Occultist: draw_n_discard_weakest.
        if card.on_deploy.kind == EffectKind::DrawNDiscardWeakest {
            let slot = self.first_deployable_lane(seat, lane);
            if slot >= 0 {
                self.lane_place_hero(slot as usize, card.clone(), seat);
                self.players[p].occult_card = card;
                self.players[p].occult_remaining = 2;
                self.players[p].occult_lane = lane;
                self.deck_try_draw_hand(seat);
                return;
            }
        }

        // Vanilla fall-through.
        let slot = self.first_deployable_lane(seat, lane);
        if slot >= 0 {
            self.lane_place_hero(slot as usize, card.clone(), seat);
            self.player_record_and_next(p, card, lane);
            return;
        }

        // No eligible lane.
        self.players[p].phase = PlayerPhase::Idle;
        self.game_deploy_done(p);
    }
    pub(crate) fn player_record_and_next(&mut self, p: usize, card: Card, lane: i64) {
        let seat = self.players[p].seat;
        self.players[p].total_deploys += 1;
        self.hand_remove_by_name(p, &card.name.clone());
        self.game_card_deployed(p, card.clone(), seat);
        self.player_try_place(p, lane);
    }
    // Occultist mid-draw.
    fn player_deploying_drew_hand_mid(&mut self, p: usize, card: Card) {
        self.hand_append(p, card);
        self.players[p].occult_remaining -= 1;
        let seat = self.players[p].seat;
        self.deck_try_draw_hand(seat);
    }
    fn player_deploying_drew_hand_final(&mut self, p: usize, card: Card) {
        // weakest: hand.cards + card, by coin+attack+hp ascending.
        let mut pool: Vec<Card> = self.players[p].hand.cards.iter().cloned().collect();
        pool.push(card.clone());
        pool.sort_by_key(|c| c.coin + c.attack + c.hp);
        let weakest = pool.into_iter().next().unwrap_or_else(|| self.players[p].occult_card.clone());

        self.hand_append(p, card);
        self.players[p].occult_remaining = 0;
        self.hand_remove_by_name(p, &weakest.name.clone());
        self.deck_add_discard(weakest);

        self.players[p].total_deploys += 1;
        let occult_name = self.players[p].occult_card.name.clone();
        self.hand_remove_by_name(p, &occult_name);
        let seat = self.players[p].seat;
        let occult_card = self.players[p].occult_card.clone();
        self.game_card_deployed(p, occult_card, seat);
        let occult_lane = self.players[p].occult_lane;
        self.player_try_place(p, occult_lane);
    }
    fn player_deploying_hand_empty(&mut self, p: usize) {
        self.players[p].occult_remaining = 0;
        self.players[p].total_deploys += 1;
        let occult_name = self.players[p].occult_card.name.clone();
        self.hand_remove_by_name(p, &occult_name);
        let seat = self.players[p].seat;
        let occult_card = self.players[p].occult_card.clone();
        self.game_card_deployed(p, occult_card, seat);
        let occult_lane = self.players[p].occult_lane;
        self.player_try_place(p, occult_lane);
    }

    pub(crate) fn player_begin_tower_quota(&mut self, p: usize, round: i64) {
        self.players[p].phase = PlayerPhase::PlacingTowers;
        self.player_try_place_tower(p, round);
    }
    pub(crate) fn player_try_place_tower(&mut self, p: usize, round: i64) {
        let seat = self.players[p].seat;
        let placed = self.players[p].towers_placed;
        let cap = (round + 1).min(3);
        let eligible = self.tower_eligible(p);
        let open = self.first_open_lane(seat);
        if placed < cap && !eligible.is_empty() && open >= 0 {
            let hero = eligible.into_iter().next().unwrap();
            self.lane_place_tower(open as usize, seat, hero.clone());
            self.players[p].towers_placed += 1;
            self.hand_remove_by_name(p, &hero.name);
            self.player_try_place_tower(p, round);
            return;
        }
        self.players[p].phase = PlayerPhase::Idle;
        self.game_tower_quota_done(p);
    }
    pub(crate) fn player_begin_hand_cap(&mut self, p: usize) {
        self.players[p].phase = PlayerPhase::CappingHand;
        self.player_try_cap(p);
    }
    pub(crate) fn player_try_cap(&mut self, p: usize) {
        let seat = self.players[p].seat;
        if self.players[p].hand.cards.len() > 3 {
            let card = self.top_hand(p);
            self.game_hand_overflow_discard(p, card.clone(), seat);
            self.hand_remove_by_name(p, &card.name);
            self.player_try_cap(p);
            return;
        }
        self.players[p].phase = PlayerPhase::Idle;
        self.game_hand_cap_done(p);
    }

    // ======================================================
    // Market arms
    // ======================================================
    pub(crate) fn market_begin_refill(&mut self) {
        self.market.phase = MarketPhase::Refilling;
        self.market_pull();
    }
    pub(crate) fn market_pull(&mut self) {
        if self.market.cards.len() < 3 {
            self.deck_try_draw_market();
        } else {
            self.market.phase = MarketPhase::Idle;
            self.game_market_refill_done();
        }
    }
    pub(crate) fn market_remove_by_name(&mut self, name: &str) {
        self.market.cards.retain(|c| c.name != name);
    }

    // ======================================================
    // Lane arms. Direct method calls on lane objects where self-contained.
    // ======================================================
    pub(crate) fn lane_place_hero(&mut self, li: usize, card: Card, team: i64) {
        // Scroll + heal_all: side.heal_all, no unit_gone.
        if card.ty == CardType::Scroll && card.on_deploy.kind == EffectKind::HealAll {
            self.lanes[li].side_mut(team).heal_all(card.on_deploy.amount);
            return;
        }
        // Item + attach_thorn: attach, discard.
        if card.ty == CardType::Item && card.on_deploy.kind == EffectKind::AttachThornAll {
            self.lanes[li].side_mut(team).attach_thorn_all();
            self.game_unit_gone(li, card, team, 0);
            return;
        }
        // Any other item or scroll: discard.
        if card.ty == CardType::Item || card.ty == CardType::Scroll {
            self.game_unit_gone(li, card, team, 0);
            return;
        }
        // Pre-deploy clear_all (Fallen Warlock).
        if card.pre_deploy.kind == EffectKind::ClearAll && card.pre_deploy.scope == EffectScope::EnemySide {
            self.lanes[li].enemy_side_mut(team).clear_units();
            self.lanes[li].side_mut(team).append(card);
            return;
        }
        // Post-append dispatch by on_deploy.kind.
        match card.on_deploy.kind {
            EffectKind::DamageAll => {
                let amt = card.on_deploy.amount;
                self.lanes[li].side_mut(team).append(card);
                self.lanes[li].enemy_side_mut(team).damage_all(amt);
            }
            EffectKind::HealAll => {
                let amt = card.on_deploy.amount;
                self.lanes[li].side_mut(team).append(card);
                self.lanes[li].side_mut(team).heal_all(amt);
            }
            EffectKind::SilenceAll => {
                self.lanes[li].side_mut(team).append(card);
                self.lanes[li].enemy_side_mut(team).silence_all();
            }
            EffectKind::BuffAll => {
                let amt = card.on_deploy.amount;
                self.lanes[li].side_mut(team).append(card);
                self.lanes[li].side_mut(team).buff_all(amt);
            }
            _ => {
                // Vanilla: just land.
                self.lanes[li].side_mut(team).append(card);
            }
        }
    }
    pub(crate) fn lane_remove_hero_by_name(&mut self, li: usize, team: i64, name: &str) {
        self.lanes[li].side_mut(team).remove_by_name(name);
    }
    pub(crate) fn lane_cross_damage(&mut self, li: usize, enemy_team: i64, amount: i64) {
        self.lanes[li].side_mut(enemy_team).damage_all(amount);
    }
    pub(crate) fn lane_place_tower(&mut self, li: usize, team: i64, card: Card) {
        self.lanes[li].side_mut(team).place_tower(card);
    }
    pub(crate) fn lane_begin_combat(&mut self, li: usize) {
        self.lanes[li].phase = LanePhase::Preparing;
        // Report already-dead units on both sides, then pair.
        let s0_dead: Vec<(Card, i64)> = self.lanes[li].side_0.units.iter()
            .filter(|u| u.current_hp == 0)
            .map(|u| (u.card.clone(), u.owner_seat)).collect();
        for (card, seat) in s0_dead { self.game_unit_gone(li, card, seat, 0); }
        let s1_dead: Vec<(Card, i64)> = self.lanes[li].side_1.units.iter()
            .filter(|u| u.current_hp == 0)
            .map(|u| (u.card.clone(), u.owner_seat)).collect();
        for (card, seat) in s1_dead { self.game_unit_gone(li, card, seat, 0); }
        self.lane_begin_pairing(li);
    }
    pub(crate) fn lane_begin_pairing(&mut self, li: usize) {
        self.lanes[li].phase = LanePhase::Combatting;
        self.lanes[li].side_0.filter_dead();
        self.lanes[li].side_1.filter_dead();
        self.lanes[li].combat_idx = 0;
        self.lanes[li].overkill_0 = 0;
        self.lanes[li].overkill_1 = 0;
        self.lane_pair_step(li);
    }
    pub(crate) fn lane_pair_step(&mut self, li: usize) {
        if !self.lanes[li].paired() {
            // Move to unopposed, absorb overkill, then tower loop.
            let ok0 = self.lanes[li].overkill_0;
            let ok1 = self.lanes[li].overkill_1;
            self.lanes[li].side_1.absorb_overkill_if_unopposed(ok0);
            self.lanes[li].side_0.absorb_overkill_if_unopposed(ok1);
            self.lanes[li].phase = LanePhase::Unopposed;
            self.lane_tower_hit(li); // unopposed entry action
            return;
        }
        let ci = self.lanes[li].combat_idx as usize;
        let f0_bypass = self.lanes[li].side_0.units[ci].card.combat_effect.kind == EffectKind::AttackBypass;
        let f1_bypass = self.lanes[li].side_1.units[ci].card.combat_effect.kind == EffectKind::AttackBypass;

        // Side 0 bypass arm.
        if f0_bypass && ci + 1 < self.lanes[li].side_1.units.len() {
            let f0_atk = self.lanes[li].side_0.units[ci].total_attack();
            let f1_atk = self.lanes[li].side_1.units[ci].total_attack();
            let f1_refl = self.lanes[li].side_1.units[ci].reflect_bonus();
            let b1_refl = self.lanes[li].side_1.units[ci + 1].reflect_bonus();
            self.lanes[li].side_1.units[ci + 1].take_damage(f0_atk + b1_refl);
            self.lanes[li].side_0.units[ci].take_damage(f1_atk + f1_refl);
            self.lanes[li].combat_idx += 1;
            self.lane_pair_step(li);
            return;
        }
        // Side 1 bypass arm.
        if f1_bypass && ci + 1 < self.lanes[li].side_0.units.len() {
            let f0_atk = self.lanes[li].side_0.units[ci].total_attack();
            let f1_atk = self.lanes[li].side_1.units[ci].total_attack();
            let f0_refl = self.lanes[li].side_0.units[ci].reflect_bonus();
            let b0_refl = self.lanes[li].side_0.units[ci + 1].reflect_bonus();
            self.lanes[li].side_0.units[ci + 1].take_damage(f1_atk + b0_refl);
            self.lanes[li].side_1.units[ci].take_damage(f0_atk + f0_refl);
            self.lanes[li].combat_idx += 1;
            self.lane_pair_step(li);
            return;
        }
        // Vanilla pair.
        let f0_atk = self.lanes[li].side_0.units[ci].total_attack();
        let f1_atk = self.lanes[li].side_1.units[ci].total_attack();
        let f0_refl = self.lanes[li].side_0.units[ci].reflect_bonus();
        let f1_refl = self.lanes[li].side_1.units[ci].reflect_bonus();
        let f0_hp = self.lanes[li].side_0.units[ci].current_hp;
        let f1_hp = self.lanes[li].side_1.units[ci].current_hp;
        self.lanes[li].overkill_0 += (f0_atk - f1_hp).max(0);
        self.lanes[li].overkill_1 += (f1_atk - f0_hp).max(0);
        self.lanes[li].side_0.units[ci].take_damage(f1_atk + f1_refl);
        self.lanes[li].side_1.units[ci].take_damage(f0_atk + f0_refl);
        self.lanes[li].combat_idx += 1;
        self.lane_pair_step(li);
    }
    pub(crate) fn lane_tower_hit(&mut self, li: usize) {
        let ci = self.lanes[li].combat_idx;
        let n0 = self.lanes[li].side_0.units.len() as i64;
        let n1 = self.lanes[li].side_1.units.len() as i64;
        if ci < n0 {
            let td = self.lanes[li].tower_damage(0);
            self.lanes[li].side_1.damage_tower(td);
            self.lanes[li].combat_idx += 1;
            self.lane_check_towers(li);
            return;
        }
        if ci < n1 {
            let td = self.lanes[li].tower_damage(1);
            self.lanes[li].side_0.damage_tower(td);
            self.lanes[li].combat_idx += 1;
            self.lane_check_towers(li);
            return;
        }
        // ci >= both.
        self.lanes[li].phase = LanePhase::Finalizing;
        let s0_dead: Vec<(Card, i64)> = self.lanes[li].side_0.units.iter()
            .filter(|u| u.current_hp == 0)
            .map(|u| (u.card.clone(), u.owner_seat)).collect();
        for (card, seat) in s0_dead { self.game_unit_gone(li, card, seat, 0); }
        let s1_dead: Vec<(Card, i64)> = self.lanes[li].side_1.units.iter()
            .filter(|u| u.current_hp == 0)
            .map(|u| (u.card.clone(), u.owner_seat)).collect();
        for (card, seat) in s1_dead { self.game_unit_gone(li, card, seat, 0); }
        self.lane_finalize(li);
    }
    pub(crate) fn lane_check_towers(&mut self, li: usize) {
        let lost = self.lanes[li].tower_lost_seat();
        if lost >= 0 {
            self.lanes[li].phase = LanePhase::Destroying;
            let tower_card = self.lanes[li].side(lost).tower_card.clone();
            self.game_tower_lost(li, lost, tower_card);
            let gone_pairs: Vec<(Card, i64, i64)> = {
                let mut v = Vec::new();
                for u in &self.lanes[li].side_0.units { v.push((u.card.clone(), u.owner_seat, u.current_hp)); }
                for u in &self.lanes[li].side_1.units { v.push((u.card.clone(), u.owner_seat, u.current_hp)); }
                v
            };
            for (card, seat, hp) in gone_pairs { self.game_unit_gone(li, card, seat, hp); }
            self.lane_finish_destroy(li, lost);
            return;
        }
        self.lane_tower_hit(li);
    }
    pub(crate) fn lane_finish_destroy(&mut self, li: usize, seat: i64) {
        self.lanes[li].side_mut(seat).clear_tower();
        self.lanes[li].side_0.clear_units();
        self.lanes[li].side_1.clear_units();
        self.lanes[li].phase = LanePhase::Unopposed;
        self.lane_tower_hit(li); // unopposed entry action
    }
    pub(crate) fn lane_finalize(&mut self, li: usize) {
        self.lanes[li].side_0.filter_dead();
        self.lanes[li].side_1.filter_dead();
        self.lanes[li].phase = LanePhase::Idle;
        self.game_combat_done(li);
    }

    // ======================================================
    // Game (main) arms
    // ======================================================
    pub(crate) fn game_start(&mut self) {
        // -> idle entry action: seed deck, kickoff.
        let deck = self.initial_deck.clone();
        self.deck_seed(deck);
        self.game_kickoff();
    }
    pub(crate) fn game_kickoff(&mut self) {
        self.phase = GamePhase::Dealing;
        self.player_begin_draw(0);
    }
    pub(crate) fn game_p_draw_done(&mut self, p: usize) {
        if self.phase != GamePhase::Dealing { return; }
        let seat = self.players[p].seat;
        if seat == 0 {
            self.player_begin_draw(1);
        } else {
            self.phase = GamePhase::Refilling;
            self.player_check_mulligan(0, self.round);
            self.player_check_mulligan(1, self.round);
            self.market_begin_refill();
        }
    }
    pub(crate) fn game_market_refill_done(&mut self) {
        if self.phase == GamePhase::Refilling {
            self.phase = GamePhase::Auctioning;
            self.game_next_auction(); // auctioning entry action
        }
    }
    pub(crate) fn game_next_auction(&mut self) {
        if self.market.cards.len() > 0 && self.auctions_run < 8 && self.auction_pass_streak < 2 {
            self.phase = GamePhase::Bidding;
            let card = self.market.cards[0].clone();
            let p0_hs = self.players[0].hand.cards.len() as i64;
            let p1_hs = self.players[1].hand.cards.len() as i64;
            let p0_bid = if self.deck.prng.below(2) == 0 { 0 } else { p0_hs };
            let p1_bid = if self.deck.prng.below(2) == 0 { 0 } else { p1_hs };
            self.market_remove_by_name(&card.name.clone());
            let winner = if p0_bid >= card.coin && p0_bid >= p1_bid { 0 }
                         else if p1_bid >= card.coin && p1_bid > p0_bid { 1 }
                         else { -1 };
            self.game_settle(card, winner);
            return;
        }
        self.phase = GamePhase::Deploying;
        let dl = self.deck.prng.below(3);
        self.player_begin_deploy_round(0, dl);
    }
    pub(crate) fn game_settle(&mut self, card: Card, winner: i64) {
        if winner >= 0 {
            let name = card.name.clone();
            self.player_win_target(winner as usize, card);
            self.auctions_run += 1;
            self.auction_pass_streak = 0;
            let cur = *self.auction_wins.get(&name).unwrap_or(&0);
            self.auction_wins.insert(name, cur + 1);
        } else {
            self.auctions_run += 1;
            self.auction_pass_streak += 1;
        }
        self.phase = GamePhase::Auctioning;
        self.game_next_auction(); // auctioning entry action re-fires
    }
    pub(crate) fn game_deploy_done(&mut self, p: usize) {
        if self.phase != GamePhase::Deploying { return; }
        let seat = self.players[p].seat;
        if seat == 0 {
            let dl = self.deck.prng.below(3);
            self.player_begin_deploy_round(1, dl);
        } else {
            self.phase = GamePhase::PlacingTowers;
            let r = self.round;
            self.player_begin_tower_quota(0, r);
        }
    }
    pub(crate) fn game_card_deployed(&mut self, _p: usize, card: Card, _seat: i64) {
        // * <: players.card_deployed -> self
        let cur = *self.deploy_counts.get(&card.name).unwrap_or(&0);
        self.deploy_counts.insert(card.name.clone(), cur + 1);
    }
    pub(crate) fn game_tower_quota_done(&mut self, p: usize) {
        if self.phase != GamePhase::PlacingTowers { return; }
        let seat = self.players[p].seat;
        if seat == 0 {
            let r = self.round;
            self.player_begin_tower_quota(1, r);
        } else {
            self.phase = GamePhase::CappingHand;
            self.player_begin_hand_cap(0);
        }
    }
    pub(crate) fn game_hand_cap_done(&mut self, p: usize) {
        if self.phase != GamePhase::CappingHand { return; }
        let seat = self.players[p].seat;
        if seat == 0 {
            self.player_begin_hand_cap(1);
        } else {
            self.phase = GamePhase::Combatting;
            self.game_resolve_combat();
        }
    }
    pub(crate) fn game_hand_overflow_discard(&mut self, _p: usize, card: Card, _seat: i64) {
        self.deck_add_discard(card);
    }
    pub(crate) fn game_tower_lost(&mut self, _li: usize, seat: i64, card: Card) {
        self.player_inc_towers_lost(seat as usize);
        self.hand_append(seat as usize, card);
    }
    pub(crate) fn game_unit_gone(&mut self, _li: usize, card: Card, seat: i64, hp: i64) {
        if hp == 0 {
            self.deck_add_discard(card);
        } else {
            self.hand_append(seat as usize, card);
        }
    }
    pub(crate) fn game_resolve_combat(&mut self) {
        println!("-- combat -- l0 tw={} l1 tw={} l2 tw={}",
            lane_tw_str(&self.lanes[0]),
            lane_tw_str(&self.lanes[1]),
            lane_tw_str(&self.lanes[2]));
        for li in 0..3 {
            for u in &self.lanes[li].side_0.units {
                println!("  l{} t0: {} hp={}", self.lanes[li].idx, u.card.name, u.current_hp);
            }
            for u in &self.lanes[li].side_1.units {
                println!("  l{} t1: {} hp={}", self.lanes[li].idx, u.card.name, u.current_hp);
            }
        }
        self.combat_dones = 0;
        for li in 0..3 { self.lane_begin_combat(li); }
    }
    pub(crate) fn game_combat_done(&mut self, _li: usize) {
        if self.phase != GamePhase::Combatting { return; }
        if self.combat_dones + 1 < 3 {
            self.combat_dones += 1;
        } else {
            self.phase = GamePhase::ResolvingRound;
            self.game_end_round();
        }
    }
    pub(crate) fn game_end_round(&mut self) {
        let p0_lost = self.players[0].towers_lost;
        let p1_lost = self.players[1].towers_lost;
        if p0_lost >= 2 || p1_lost >= 2 || self.round >= 11 {
            self.phase = GamePhase::End;
            self.game_end_entry();
            return;
        }
        self.round += 1;
        self.auctions_run = 0;
        self.auction_pass_streak = 0;
        self.phase = GamePhase::Dealing;
        self.player_begin_draw(0);
    }
    pub(crate) fn game_winner(&self) -> i64 {
        let a = self.players[0].towers_lost >= 2;
        let b = self.players[1].towers_lost >= 2;
        if a && b { -1 }
        else if a { 1 }
        else if b { 0 }
        else { -1 }
    }
    pub(crate) fn game_end_entry(&self) {
        for p in &self.players {
            let name = format!("p{}", p.seat);
            println!("{} hand:", name);
            for c in &p.hand.cards { println!("{}", c.name); }
            println!("{} won:", name);
            for c in &p.won { println!("{}", c.name); }
        }
        println!("market:");
        for c in &self.market.cards { println!("{}", c.name); }
        println!("discard:");
        for c in &self.deck.discard { println!("{}", c.name); }
        println!("deck:");
        for c in &self.deck.cards { println!("{}", c.name); }
        for l in &self.lanes {
            println!("lane{} t0/t1:", l.idx);
            for u in &l.side_0.units { println!("{}", u.card.name); }
            for u in &l.side_1.units { println!("{}", u.card.name); }
        }
        println!("towers l0 {} l1 {} l2 {}",
            lane_tw_str(&self.lanes[0]), lane_tw_str(&self.lanes[1]), lane_tw_str(&self.lanes[2]));
        println!("tower cards: l0[{}] l1[{}] l2[{}]",
            lane_names_str(&self.lanes[0]), lane_names_str(&self.lanes[1]), lane_names_str(&self.lanes[2]));
        println!();
        println!("=== game result (Python-parity output) ===");
        println!("winner: {}, rounds: {}", self.game_winner(), self.round + 1);
        println!("towers_destroyed p0: {}, p1: {}",
            self.players[1].towers_lost, self.players[0].towers_lost);
        println!("deploy_counts (total p0={} p1={}):",
            self.players[0].total_deploys, self.players[1].total_deploys);
        for c in &self.initial_deck {
            let n = *self.deploy_counts.get(&c.name).unwrap_or(&0);
            println!("  {}{}", pad_right(&format!("{}:", c.name), 18), n);
        }
        println!("auction_wins (total p0={} p1={}):",
            self.players[0].total_auction_wins, self.players[1].total_auction_wins);
        for c in &self.initial_deck {
            let n = *self.auction_wins.get(&c.name).unwrap_or(&0);
            println!("  {}{}", pad_right(&format!("{}:", c.name), 18), n);
        }
        println!("mulligans: [{}, {}]", self.players[0].mulligans, self.players[1].mulligans);
    }
}

// ============================================================
// Formatting helpers (lane_tw_str, lane_names_str, pad_right).
// ============================================================

fn lane_tw_str(l: &Lane) -> String {
    format!("{}/{}", l.side_0.tower_hp, l.side_1.tower_hp)
}
fn lane_names_str(l: &Lane) -> String {
    format!("{}/{}", l.side_0.tower_card.name, l.side_1.tower_card.name)
}
fn pad_right(s: &str, width: usize) -> String {
    if s.len() >= width { s.to_string() }
    else {
        let mut out = s.to_string();
        for _ in 0..(width - s.len()) { out.push(' '); }
        out
    }
}

// ============================================================
// Deck table and main.
// ============================================================

fn make_initial_deck() -> Vec<Card> {
    let dmg_enemy_2 = DeployEffect { kind: EffectKind::DamageAll, scope: EffectScope::EnemySide, amount: 2 };
    let dmg_enemy_5 = DeployEffect { kind: EffectKind::DamageAll, scope: EffectScope::EnemySide, amount: 5 };
    let heal_own_2 = DeployEffect { kind: EffectKind::HealAll, scope: EffectScope::OwnSide, amount: 2 };
    let silence_enemy = DeployEffect { kind: EffectKind::SilenceAll, scope: EffectScope::EnemySide, amount: 0 };
    let buff_own_1 = DeployEffect { kind: EffectKind::BuffAll, scope: EffectScope::OwnSide, amount: 1 };
    let clear_enemy = DeployEffect { kind: EffectKind::ClearAll, scope: EffectScope::EnemySide, amount: 0 };
    let thorn_own = DeployEffect { kind: EffectKind::AttachThornAll, scope: EffectScope::OwnSide, amount: 0 };
    let cross_dmg_2 = DeployEffect { kind: EffectKind::CrossDamageAll, scope: EffectScope::EnemySide, amount: 2 };
    let move_next = DeployEffect { kind: EffectKind::MoveFromNextLaneFront, scope: EffectScope::OwnSide, amount: 0 };
    let draw_2_discard_1 = DeployEffect { kind: EffectKind::DrawNDiscardWeakest, scope: EffectScope::OwnSide, amount: 2 };
    let bypass = CombatEffect { kind: EffectKind::AttackBypass, amount: 0 };
    let dmg_cap_2 = CombatEffect { kind: EffectKind::DamageCap, amount: 2 };
    let reflect_1 = CombatEffect { kind: EffectKind::ReflectBonus, amount: 1 };
    let tower_plus_2 = CombatEffect { kind: EffectKind::TowerBonus, amount: 2 };

    let mk = |name: &str, ty: CardType, attack: i64, hp: i64, coin: i64,
              pre: DeployEffect, on: DeployEffect, ce: CombatEffect| -> Card {
        Card { name: name.to_string(), ty, attack, hp, coin,
               pre_deploy: pre, on_deploy: on, combat_effect: ce }
    };

    vec![
        mk("Blood Demon",      CardType::Hero,    5, 4, 3, NO_EFFECT,   dmg_enemy_2,       NO_COMBAT),
        mk("Storm Spirit",     CardType::Hero,    5, 3, 3, NO_EFFECT,   cross_dmg_2,       NO_COMBAT),
        mk("Marksman",         CardType::Hero,    4, 4, 4, NO_EFFECT,   NO_EFFECT,         bypass),
        mk("Axe King",         CardType::Hero,    4, 8, 4, NO_EFFECT,   NO_EFFECT,         NO_COMBAT),
        mk("Tide Beast",       CardType::Hero,    3, 9, 5, NO_EFFECT,   silence_enemy,     NO_COMBAT),
        mk("Fallen Warlock",   CardType::Hero,    4, 5, 5, clear_enemy, NO_EFFECT,         NO_COMBAT),
        mk("Occultist",        CardType::Hero,    3, 4, 4, NO_EFFECT,   draw_2_discard_1,  NO_COMBAT),
        mk("Faerie Dragon",    CardType::Hero,    3, 4, 3, NO_EFFECT,   NO_EFFECT,         dmg_cap_2),
        mk("Mage Slayer",      CardType::Hero,    6, 6, 5, NO_EFFECT,   dmg_enemy_5,       NO_COMBAT),
        mk("Phantom Blade",    CardType::Hero,    8, 6, 6, NO_EFFECT,   NO_EFFECT,         tower_plus_2),
        mk("Kobold Boss",      CardType::Neutral, 1, 2, 2, NO_EFFECT,   buff_own_1,        NO_COMBAT),
        mk("Wolf Leader",      CardType::Neutral, 3, 2, 3, NO_EFFECT,   buff_own_1,        NO_COMBAT),
        mk("Satyr Torturer",   CardType::Neutral, 2, 3, 2, NO_EFFECT,   heal_own_2,        NO_COMBAT),
        mk("Centaur Champion", CardType::Neutral, 2, 5, 3, NO_EFFECT,   NO_EFFECT,         reflect_1),
        mk("Hell Bear",        CardType::Neutral, 4, 3, 3, NO_EFFECT,   dmg_enemy_2,       NO_COMBAT),
        mk("Blink Blade",      CardType::Item,    0, 0, 3, NO_EFFECT,   move_next,         NO_COMBAT),
        mk("Thorn Armor",      CardType::Item,    0, 0, 3, NO_EFFECT,   thorn_own,         NO_COMBAT),
        mk("Return Scroll",    CardType::Scroll,  0, 0, 3, NO_EFFECT,   heal_own_2,        NO_COMBAT),
    ]
}

fn main() {
    let initial = make_initial_deck();
    let mut game = Game::new(42, initial);
    game.game_start();
}

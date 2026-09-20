package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.util.List;
import java.util.Optional;

/**
 * Picks the parser for a message.
 *
 * Order matters: a message is handled by the first parser that claims to
 * support it.  Parsers should be as specific as possible in their supports()
 * check so that a message is never accidentally handled by the wrong parser.
 *
 * Parsers registered here:
 *   HdfcSmsParser  — sender AD-HDFCBK-S, channel sms
 *   IciciSmsParser — sender VM-ICICIB-T, channel sms
 *   EmailParser    — channel email (both HDFC and ICICI alert emails)
 *
 * Unknown senders and non-bank promotional/OTP messages fall through to
 * Optional.empty(), which is the correct outcome.
 */
public final class Parsers {

    private final List<MessageParser> parsers;

    public Parsers() {
        this(List.of(new HdfcSmsParser(), new IciciSmsParser(), new EmailParser()));
    }

    public Parsers(List<MessageParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public Optional<ParsedTxn> parse(RawMessage m) {
        for (MessageParser p : parsers) {
            if (p.supports(m)) return p.parse(m);
        }
        return Optional.empty();
    }
}

/*
Copyright 2018 Cedric Liegeois

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * Neither the name of the copyright holder nor the names of other
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package io.omam.halo;

import static io.omam.halo.MulticastDns.FLAGS_QR_MASK;
import static io.omam.halo.MulticastDns.FLAGS_QR_QUERY;
import static io.omam.halo.MulticastDns.FLAGS_QR_RESPONSE;
import static io.omam.halo.MulticastDns.TYPE_A;
import static io.omam.halo.MulticastDns.TYPE_AAAA;
import static io.omam.halo.MulticastDns.TYPE_PTR;
import static io.omam.halo.MulticastDns.TYPE_SRV;
import static io.omam.halo.MulticastDns.TYPE_TXT;
import static io.omam.halo.MulticastDns.encodeClass;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A DNS message.
 */
final class DnsMessage {

    /**
     * {@code DnsMessage} builder.
     */
    static final class Builder implements Supplier<DnsMessage> {

        /** DNS message flags. */
        private final short flags;

        /** list of questions. */
        private final List<DnsQuestion> questions;

        /** list of answers. */
        private final List<DnsAnswer> answers;

        /** list of authorities. */
        private final List<DnsRecord> authorities;

        /** list of additional records. */
        private final List<DnsRecord> additional;

        /**
         * Constructor.
         *
         * @param flag primary flag
         * @param otherFlags other flags
         */
        Builder(final short flag, final short[] otherFlags) {
            short f = flag;
            for (final short of : otherFlags) {
                f = (short) (f | of);
            }
            flags = f;
            questions = new ArrayList<>();
            answers = new ArrayList<>();
            authorities = new ArrayList<>();
            additional = new ArrayList<>();
        }

        @Override
        public final DnsMessage get() {
            return new DnsMessage(flags, questions, answers, authorities, additional);
        }

        /**
         * Adds the given additional record.
         *
         * @param record record
         * @return this
         */
        final Builder addAdditional(final DnsRecord record) {
            additional.add(record);
            return this;
        }

        /**
         * Adds the given answer to the given message.
         * <p>
         * Answer is added only if it is not {@link DnsRecord#suppressedBy(DnsMessage) suppressed} by the message
         * being answered.
         *
         * @param msg DNS message which is being answered, can be null
         * @param answer answer
         * @return this
         */
        final Builder addAnswer(final DnsMessage msg, final DnsRecord answer) {
            if (msg == null || !answer.suppressedBy(msg)) {
                answers.add(DnsAnswer.unstamped(answer));
            }
            return this;
        }

        /**
         * Adds the given answer.
         * <p>
         * If a stamp is provided, answer if given only if it is not {@link DnsRecord#isExpired(Instant) expired}.
         *
         * @param answer answer
         * @param stamp answer stamp if any
         * @return this
         */
        final Builder addAnswer(final DnsRecord answer, final Optional<Instant> stamp) {
            if (stamp.isPresent()) {
                if (!answer.isExpired(stamp.get())) {
                    answers.add(DnsAnswer.stamped(answer, stamp.get()));
                }
            } else {
                answers.add(DnsAnswer.unstamped(answer));
            }
            return this;
        }

        /**
         * Adds the given authority.
         *
         * @param authority authority
         * @return this
         */
        final Builder addAuthority(final DnsRecord authority) {
            authorities.add(authority);
            return this;

        }

        /**
         * Adds the given question.
         *
         * @param question question
         * @return this
         */
        final Builder addQuestion(final DnsQuestion question) {
            questions.add(question);
            return this;
        }

    }

    /**
     * An answer to a DNS message.
     */
    static final class DnsAnswer {

        /** DNS record holding the answer. */
        private final DnsRecord record;

        /** answer stamp, if any. */
        private final Optional<Instant> stamp;

        /**
         * Constructor.
         *
         * @param aRecord DNS record holding the answer
         * @param aStamp answer stamp, if any
         */
        private DnsAnswer(final DnsRecord aRecord, final Optional<Instant> aStamp) {
            stamp = aStamp;
            record = aRecord;
        }

        /**
         * Builds a stamped answer.
         *
         * @param record DNS record holding the answer
         * @param stamp answer stamp
         * @return a stamped answer
         */
        static DnsAnswer stamped(final DnsRecord record, final Instant stamp) {
            return new DnsAnswer(record, Optional.of(stamp));
        }

        /**
         * Builds an unstamped answer.
         *
         * @param record DNS record holding the answer
         * @return an unstamped answer
         */
        static DnsAnswer unstamped(final DnsRecord record) {
            return new DnsAnswer(record, Optional.empty());
        }

        @Override
        public final String toString() {
            return record.toString();
        }

        /**
         * @return DNS record holding the answer.
         */
        final DnsRecord record() {
            return record;
        }

        /**
         * @return answer stamp, if any.
         */
        final Optional<Instant> stamp() {
            return stamp;
        }

    }

    /** DNS message flags. */
    private final short flags;

    /** list of questions. */
    private final List<DnsQuestion> questions;

    /** list of answers. */
    private final List<DnsAnswer> answers;

    /** list of authorities. */
    private final List<DnsRecord> authorities;

    /** list of additional records. */
    private final List<DnsRecord> additional;

    /**
     * Constructor.
     *
     * @param someFlags DNS message flags
     * @param someQuestions list of questions
     * @param someAnswers list of answers
     * @param someAuthorities list of authorities
     * @param someAdditional list of additional records
     */
    DnsMessage(final short someFlags, final List<DnsQuestion> someQuestions, final List<DnsAnswer> someAnswers,
            final List<DnsRecord> someAuthorities, final List<DnsRecord> someAdditional) {
        flags = someFlags;
        questions = someQuestions;
        answers = someAnswers;
        authorities = someAuthorities;
        additional = someAdditional;
    }

    /**
     * Decodes the given bytes into a {@code DnsMessage}.
     *
     * @param bytes bytes to decode
     * @param now current instant
     * @return the decoded {@code DnsMessage}
     * @throws IOException in case of I/O error during decoding
     */
    static DnsMessage decode(final byte[] bytes, final Instant now) throws IOException {
        try (final MessageInputStream is = new MessageInputStream(bytes)) {
            /*
             * header is 6 shorts for the ID, flags, number of questions, number of answers, number of authorities
             * and number of additional. ID is irrelevant for mDNS.
             */
            is.readShort();
            final short flags = (short) is.readShort();
            final short numQuestions = (short) is.readShort();
            final short numAnswers = (short) is.readShort();
            final short numAuthorities = (short) is.readShort();
            final short numAdditional = (short) is.readShort();

            final List<DnsQuestion> questions = new ArrayList<>();
            for (int i = 0; i < numQuestions; i++) {
                final String name = is.readName();
                final short type = (short) is.readShort();
                final short clazz = (short) is.readShort();
                final DnsQuestion question = new DnsQuestion(name, type, clazz);
                questions.add(question);
            }
            final List<DnsAnswer> answers =
                    readRecords(is, numAnswers, now).stream().map(DnsAnswer::unstamped).collect(
                            Collectors.toList());
            final List<DnsRecord> authorities = readRecords(is, numAuthorities, now);
            final List<DnsRecord> additional = readRecords(is, numAdditional, now);
            return new DnsMessage(flags, questions, answers, authorities, additional);
        } catch (final BufferUnderflowException e) {
            throw new IOException(e);
        }
    }

    /**
     * Returns a new {@link Builder builder} to build a DNS {@link DnsMessage#isQuery() query}.
     *
     * @param flags additional flags (on top of FLAGS_QR_QUERY)
     * @return a new {@link Builder builder}
     */
    static Builder query(final short... flags) {
        return new Builder(FLAGS_QR_QUERY, flags);
    }

    /**
     * Returns a new {@link Builder builder} to build a DNS {@link DnsMessage#isResponse() response}.
     *
     * @param flags additional flags (on top of FLAGS_QR_RESPONSE)
     * @return a new {@link Builder builder}
     */
    static Builder response(final short... flags) {
        return new Builder(FLAGS_QR_RESPONSE, flags);
    }

    /**
     * Reads one {@link DnsRecord} from the given stream.
     *
     * @param is stream
     * @param now current instant
     * @return the read DNS record if any
     * @throws IOException in case of I/O error
     */
    private static Optional<DnsRecord> readRecord(final MessageInputStream is, final Instant now)
            throws IOException {
        final String name = is.readName();
        final short type = (short) is.readShort();
        final short clazz = (short) is.readShort();
        final Duration ttl = Duration.ofSeconds(is.readInt());
        final short length = (short) is.readShort();

        final DnsRecord record;
        switch (type) {
            case TYPE_A:
                record = new AddressRecord(name, clazz, ttl, now, InetAddress.getByAddress(is.readBytes(length)));
                break;
            case TYPE_AAAA:
                record = new AddressRecord(name, clazz, ttl, now, InetAddress.getByAddress(is.readBytes(length)));
                break;
            case TYPE_PTR:
                record = new PtrRecord(name, clazz, ttl, now, is.readName());
                break;
            case TYPE_SRV:
                /* ignore priority. */
                is.readShort();
                /* ignore priority. */
                is.readShort();
                final short port = (short) is.readShort();
                final String server = is.readName();
                record = new SrvRecord(name, clazz, ttl, now, port, server);
                break;
            case TYPE_TXT:
                record = new TxtRecord(name, clazz, ttl, now, AttributesCodec.decode(is, length));
                break;
            default:
                /*
                 * ignore unknown types: skip the payload for the resource record so the next records can be parsed
                 * correctly.
                 */
                final long skipped = is.skip(length);
                if (skipped != length) {
                    throw new IOException("Failed to skip over ignored record.");
                }
                record = null;
                break;
        }
        return Optional.ofNullable(record);
    }

    /**
     * Reads all {@link DnsRecord}(s) from the given stream.
     *
     * @param is stream
     * @param size number of record(s) to read
     * @param now current instant
     * @return all read DNS record(s)
     * @throws IOException in case of I/O error
     */
    private static List<DnsRecord> readRecords(final MessageInputStream is, final int size, final Instant now)
            throws IOException {
        final List<DnsRecord> records = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            readRecord(is, now).ifPresent(records::add);
        }
        return records;
    }

    /**
     * Writes the given question to the given stream.
     *
     * @param question question
     * @param mos stream
     */
    private static void write(final DnsQuestion question, final MessageOutputStream mos) {
        mos.writeName(question.name());
        mos.writeShort(question.type());
        mos.writeShort(encodeClass(question.clazz(), question.isUnique()));
    }

    /**
     * Writes the given record to the given stream.
     *
     * @param record record
     * @param stamp record stamp if any
     * @param mos stream
     */
    private static void write(final DnsRecord record, final Optional<Instant> stamp,
            final MessageOutputStream mos) {
        mos.writeName(record.name());
        mos.writeShort(record.type());
        mos.writeShort(encodeClass(record.clazz(), record.isUnique()));
        if (stamp.isPresent()) {
            mos.writeInt((int) record.remainingTtl(stamp.get()).getSeconds());
        } else {
            mos.writeInt((int) record.ttl().getSeconds());
        }

        /*
         * next two bytes is size of record specific payload. first write the record, then calculate the size.
         */
        final int sizePos = mos.position();
        mos.skip(2);
        final int startPos = mos.position();
        record.write(mos);
        final int endPos = mos.position();
        mos.writeShort(sizePos, (short) (endPos - startPos));
    }

    @Override
    public final String toString() {
        final StringBuilder sb = new StringBuilder("DNS ");
        if (isQuery()) {
            sb.append("query with ").append(questions.size()).append(" question(s): ").append(
                    questions.toString());
        } else {
            sb.append("response with ").append(answers.size()).append(" answer(s): ").append(answers.toString());
        }
        return sb.toString();
    }

    /**
     * @return all additional record(s) of this message.
     */
    final List<DnsRecord> additional() {
        return Collections.unmodifiableList(additional);
    }

    /**
     * @return all answer(s) of this message.
     */
    final List<DnsRecord> answers() {
        return Collections.unmodifiableList(answers.stream().map(DnsAnswer::record).collect(Collectors.toList()));
    }

    /**
     * @return all authority(s) of this message.
     */
    final List<DnsRecord> authorities() {
        return Collections.unmodifiableList(authorities);
    }

    /**
     * Encodes this {@code DnsMessage} in binary format.
     *
     * @return bytes
     */
    final byte[] encode() {
        try (final MessageOutputStream mos = new MessageOutputStream()) {
            mos.writeShort((short) 0);

            mos.writeShort(flags);
            mos.writeShort((short) questions.size());
            mos.writeShort((short) answers.size());
            mos.writeShort((short) authorities.size());
            mos.writeShort((short) additional.size());

            questions.forEach(q -> write(q, mos));
            answers.forEach(a -> write(a.record(), a.stamp(), mos));
            authorities.forEach(a -> write(a, Optional.empty(), mos));
            additional.forEach(a -> write(a, Optional.empty(), mos));

            return mos.toByteArray();
        } catch (final IOException e) {
            /* not possible. */
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return the flags of this DNS message.
     */
    final short flags() {
        return flags;
    }

    /**
     * @return true if this is a query.
     */
    final boolean isQuery() {
        return (flags & FLAGS_QR_MASK) == FLAGS_QR_QUERY;
    }

    /**
     * @return true if this is a response.
     */
    final boolean isResponse() {
        return (flags & FLAGS_QR_MASK) == FLAGS_QR_RESPONSE;
    }

    /**
     * @return all question(s) of this message.
     */
    final List<DnsQuestion> questions() {
        return Collections.unmodifiableList(questions);
    }

}
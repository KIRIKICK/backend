package org.kirikick.api.auth.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Coordinates temporary OAuth and signup slots without retaining framework session data. */
@RequiredArgsConstructor
public final class RedisFlowClaimGuard {

  private static final long MARGIN = Duration.ofMinutes(1).toMillis();
  private static final long FLOW_IDLE = Duration.ofMinutes(10).toMillis();
  private static final long PROOF_IDLE = Duration.ofMinutes(5).toMillis();
  private static final long CLAIM_LEASE = Duration.ofMinutes(5).toMillis();
  private static final DefaultRedisScript<String> SCRIPT =
      new DefaultRedisScript<>(
          """
          local function n(v) local x=tonumber(v); if x==nil or x<0 or x~=math.floor(x) then return nil end; return x end
          local function h(f) local v=redis.call('HGET',KEYS[1],f); if v==false then return nil end; return v end
          local function fields(expected) local a=redis.call('HKEYS',KEYS[1]); for _,k in ipairs(a) do if expected[k]==nil then return false end end; for k,_ in pairs(expected) do if redis.call('HEXISTS',KEYS[1],k)==0 then return false end end; return true end
          local function load()
           if redis.call('EXISTS',KEYS[1])==0 then return {k='MISSING'} end
           local k=h('kind'); local i=h('id'); local d=n(h('deadline'))
           if k==nil or i==nil or i=='' or d==nil then return {k='BAD'} end
           local c=h('claimId'); local cd=n(h('claimDeadline'))
           if k=='FLOW' then if (c==nil and cd==nil and fields({kind=true,id=true,deadline=true})) or (c~=nil and c~='' and cd~=nil and fields({kind=true,id=true,deadline=true,claimId=true,claimDeadline=true})) then return {k=k,i=i,d=d,c=c,cd=cd} end
           elseif k=='PROOF' and c==nil and cd==nil and fields({kind=true,id=true,deadline=true}) then return {k=k,i=i,d=d}
           elseif k=='SIGNUP_CLAIMED' and c~=nil and c~='' and cd~=nil and fields({kind=true,id=true,deadline=true,claimId=true,claimDeadline=true}) then return {k=k,i=i,d=d,c=c,cd=cd} end
           return {k='BAD'}
          end
          local function put(k,i,d,c,cd,t) redis.call('DEL',KEYS[1]); redis.call('HSET',KEYS[1],'kind',k,'id',i,'deadline',d); if c~='' then redis.call('HSET',KEYS[1],'claimId',c,'claimDeadline',cd) end; redis.call('PEXPIRE',KEYS[1],t) end
          local op,now,id,d,c,act,pid,pd=ARGV[1],n(ARGV[2]),ARGV[3],n(ARGV[4]),ARGV[5],ARGV[6],ARGV[7],n(ARGV[8])
          if now==nil or id=='' or d==nil then return 'UNAVAILABLE' end
          local s=load(); if s.k=='BAD' then return 'UNAVAILABLE' end
          if op=='BEGIN' then if s.k=='SIGNUP_CLAIMED' and s.cd>now then return 'SIGNUP_IN_PROGRESS' end; if d<=now then return 'UNAVAILABLE' end; put('FLOW',id,d,'','',d-now+%d+%d); return 'STARTED' end
          if op=='CLAIM_OAUTH' then if s.k~='FLOW' or s.i~=id or s.d~=d then return 'STALE' end; if s.d<=now then redis.call('DEL',KEYS[1]); return 'EXPIRED_CLEANUP_PERMIT' end; if s.c==nil then if c=='' then return 'UNAVAILABLE' end; redis.call('HSET',KEYS[1],'claimId',c,'claimDeadline',d); redis.call('PEXPIRE',KEYS[1],d-now+%d+%d); return 'CLAIMED' end; if s.c==c then return 'CLAIMED' end; return 'REPLAYED' end
          if op=='FINISH_OAUTH' then if s.k~='FLOW' or s.i~=id or s.d~=d or s.c~=c then return 'STALE' end; if act=='END' then redis.call('DEL',KEYS[1]); return 'END_CLEANUP_PERMIT' end; if act=='TO_PROOF' and pid~='' and pd~=nil and pd>now then put('PROOF',pid,pd,'','',pd-now+%d+%d); return 'PROOF_TRANSITION_PERMIT' end; return 'UNAVAILABLE' end
          if op=='INSPECT_PROOF' then if s.i~=id or s.d~=d then return 'STALE' end; if s.k=='PROOF' then if s.d<=now then redis.call('DEL',KEYS[1]); return 'EXPIRED_CLEANUP_PERMIT' end; return 'CURRENT' end; if s.k=='SIGNUP_CLAIMED' then if s.cd<=now then redis.call('DEL',KEYS[1]); return 'EXPIRED_CLAIM_CLEANUP_PERMIT' end; return 'ALREADY_CLAIMED' end; return 'STALE' end
          if op=='FINISH_PROOF' then if s.k=='PROOF' and s.i==id and s.d==d then redis.call('DEL',KEYS[1]); return 'CLEANUP_PERMIT' end; return 'STALE' end
          if op=='CLAIM_SIGNUP' then if s.i~=id or s.d~=d then return 'STALE' end; if s.k=='PROOF' then if s.d<=now then redis.call('DEL',KEYS[1]); return 'EXPIRED_CLEANUP_PERMIT' end; if c=='' then return 'UNAVAILABLE' end; local cd=now+%d; put('SIGNUP_CLAIMED',id,d,c,cd,cd-now+%d+%d); return 'CLAIMED' end; if s.k=='SIGNUP_CLAIMED' then if s.cd<=now then redis.call('DEL',KEYS[1]); return 'EXPIRED_CLAIM_CLEANUP_PERMIT' end; return 'ALREADY_CLAIMED' end; return 'STALE' end
          if op=='FINISH_SIGNUP' then if s.k~='SIGNUP_CLAIMED' or s.i~=id or s.d~=d or s.c~=c then return 'STALE' end; redis.call('DEL',KEYS[1]); if s.cd<=now then return 'EXPIRED_CLEANUP_PERMIT' end; return 'FINISHED_CLEANUP_PERMIT' end
          return 'UNAVAILABLE'
          """
              .formatted(
                  FLOW_IDLE,
                  MARGIN,
                  FLOW_IDLE,
                  MARGIN,
                  PROOF_IDLE,
                  MARGIN,
                  CLAIM_LEASE,
                  PROOF_IDLE,
                  MARGIN),
          String.class);

  private final StringRedisTemplate redis;
  private final String namespace;

  public BeginOAuthResult beginOAuth(UUID slotId, UUID flowId, Instant deadline, Instant now) {
    return call(
        BeginOAuthResult.class, "BEGIN", slotId, flowId, deadline, null, null, null, null, now);
  }

  public ClaimOAuthResult claimOAuth(
      UUID slotId, UUID flowId, Instant deadline, UUID claimId, Instant now) {
    return call(
        ClaimOAuthResult.class,
        "CLAIM_OAUTH",
        slotId,
        flowId,
        deadline,
        claimId,
        null,
        null,
        null,
        now);
  }

  public FinishOAuthResult finishOAuth(
      UUID slotId,
      UUID flowId,
      Instant deadline,
      UUID claimId,
      OAuthFinish action,
      UUID nextProofId,
      Instant proofDeadline,
      Instant now) {
    return call(
        FinishOAuthResult.class,
        "FINISH_OAUTH",
        slotId,
        flowId,
        deadline,
        claimId,
        action,
        nextProofId,
        proofDeadline,
        now);
  }

  public InspectProofResult inspectProof(UUID slotId, UUID proofId, Instant deadline, Instant now) {
    return call(
        InspectProofResult.class,
        "INSPECT_PROOF",
        slotId,
        proofId,
        deadline,
        null,
        null,
        null,
        null,
        now);
  }

  public FinishProofResult finishProof(UUID slotId, UUID proofId, Instant deadline, Instant now) {
    return call(
        FinishProofResult.class,
        "FINISH_PROOF",
        slotId,
        proofId,
        deadline,
        null,
        null,
        null,
        null,
        now);
  }

  public ClaimSignupResult claimSignup(
      UUID slotId, UUID proofId, Instant deadline, UUID claimId, Instant now) {
    return call(
        ClaimSignupResult.class,
        "CLAIM_SIGNUP",
        slotId,
        proofId,
        deadline,
        claimId,
        null,
        null,
        null,
        now);
  }

  public FinishSignupResult finishSignup(
      UUID slotId, UUID proofId, Instant deadline, UUID claimId, Instant now) {
    return call(
        FinishSignupResult.class,
        "FINISH_SIGNUP",
        slotId,
        proofId,
        deadline,
        claimId,
        null,
        null,
        null,
        now);
  }

  private <T extends Enum<T>> T call(
      Class<T> type,
      String op,
      UUID slotId,
      UUID flowOrProofId,
      Instant deadline,
      UUID claimId,
      OAuthFinish action,
      UUID nextProofId,
      Instant proofDeadline,
      Instant now) {
    boolean needsClaim =
        op.equals("CLAIM_OAUTH")
            || op.equals("FINISH_OAUTH")
            || op.equals("CLAIM_SIGNUP")
            || op.equals("FINISH_SIGNUP");
    if (slotId == null
        || flowOrProofId == null
        || deadline == null
        || now == null
        || (needsClaim && claimId == null)
        || (op.equals("FINISH_OAUTH")
            && (action == null
                || (action == OAuthFinish.TO_PROOF
                    && (nextProofId == null || proofDeadline == null))))) {
      throw unavailable(null);
    }
    String value;
    try {
      value =
          redis.execute(
              SCRIPT,
              List.of(namespace + ":temporary-slot:" + slotId),
              op,
              ms(now),
              flowOrProofId.toString(),
              ms(deadline),
              claimId == null ? "" : claimId.toString(),
              action == null ? "" : action.name(),
              nextProofId == null ? "" : nextProofId.toString(),
              proofDeadline == null ? "" : ms(proofDeadline));
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    if (value == null || value.equals("UNAVAILABLE")) {
      throw unavailable(null);
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  private static String ms(Instant value) {
    return Long.toString(value.toEpochMilli());
  }

  private static SessionStateUnavailableException unavailable(Throwable cause) {
    return new SessionStateUnavailableException(
        cause == null ? new IllegalStateException("유효하지 않은 임시 인증 상태") : cause);
  }

  public enum OAuthFinish {
    END,
    TO_PROOF
  }

  public enum BeginOAuthResult {
    STARTED,
    SIGNUP_IN_PROGRESS
  }

  public enum ClaimOAuthResult {
    CLAIMED,
    EXPIRED_CLEANUP_PERMIT,
    REPLAYED,
    STALE
  }

  public enum FinishOAuthResult {
    END_CLEANUP_PERMIT,
    PROOF_TRANSITION_PERMIT,
    STALE
  }

  public enum InspectProofResult {
    CURRENT,
    EXPIRED_CLEANUP_PERMIT,
    ALREADY_CLAIMED,
    EXPIRED_CLAIM_CLEANUP_PERMIT,
    STALE
  }

  public enum FinishProofResult {
    CLEANUP_PERMIT,
    STALE
  }

  public enum ClaimSignupResult {
    CLAIMED,
    EXPIRED_CLEANUP_PERMIT,
    ALREADY_CLAIMED,
    EXPIRED_CLAIM_CLEANUP_PERMIT,
    STALE
  }

  public enum FinishSignupResult {
    FINISHED_CLEANUP_PERMIT,
    EXPIRED_CLEANUP_PERMIT,
    STALE
  }
}

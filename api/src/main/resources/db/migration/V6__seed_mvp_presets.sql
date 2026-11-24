do $$
declare
    inserted_question_id bigint;
begin
    if exists (
        select 1 from question_presets
        where question_text in (
            '{name}이(가) 주말에 제일 많이 하는 일은?',
            '{name}이(가) 제일 좋아하는 야식은?',
            '{name}이(가) 하루 동안 투명인간이 된다면 가장 먼저 할 일은?',
            '{name}이(가) 요즘 제일 자주 듣는 음악은?',
            '{name}이(가) 아침에 눈뜨자마자 하는 일은?',
            '{name}이(가) 가장 가고 싶어 하는 여행지는?',
            '{name}이(가) 스트레스를 받을 때 가장 자주 하는 일은?',
            '{name}이(가) 제일 좋아하는 계절은?',
            '{name}이(가) 카페에서 제일 자주 시키는 음료는?',
            '{name}이(가) 갖고 싶은 초능력은?',
            '{name}이(가) 복권에 당첨되면 제일 먼저 할 일은?',
            '{name}이(가) 키우고 싶은 동물은?'
        )
    ) or exists (
        select 1 from reward_presets
        where (title, description) in (
            ('리액션 한가득', '방장이 1등에게 신나게 리액션해 줘요.'),
            ('칭찬 세 마디', '방장이 1등에게 칭찬 세 마디를 들려줘요.'),
            ('메뉴 결정권', '방장과 밥 먹을 때, 1등이 메뉴를 골라요.')
        )
    ) then
        raise exception 'MVP presets may already be registered; V6 stopped without merging'
            using hint = 'Review existing question_presets and reward_presets before applying V6.';
    end if;

    insert into question_presets(question_text)
        values ('{name}이(가) 주말에 제일 많이 하는 일은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '넷플릭스 정주행');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '카페에서 수다');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '하루 종일 자기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '즉흥 여행');

    insert into question_presets(question_text)
        values ('{name}이(가) 제일 좋아하는 야식은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '치킨');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '떡볶이');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '라면');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '족발');

    insert into question_presets(question_text)
        values ('{name}이(가) 하루 동안 투명인간이 된다면 가장 먼저 할 일은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '몰래 영화관 가기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '친구들 놀래키기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '놀이공원 프리패스');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '집에서 낮잠');

    insert into question_presets(question_text)
        values ('{name}이(가) 요즘 제일 자주 듣는 음악은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '발라드');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '케이팝 댄스');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '힙합');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '인디 밴드');

    insert into question_presets(question_text)
        values ('{name}이(가) 아침에 눈뜨자마자 하는 일은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '휴대폰 확인');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '물 한 잔');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '다시 잠들기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '스트레칭');

    insert into question_presets(question_text)
        values ('{name}이(가) 가장 가고 싶어 하는 여행지는?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '제주도');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '일본');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '유럽');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '동남아');

    insert into question_presets(question_text)
        values ('{name}이(가) 스트레스를 받을 때 가장 자주 하는 일은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '매운 거 먹기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '잠자기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '수다 떨기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '운동하기');

    insert into question_presets(question_text)
        values ('{name}이(가) 제일 좋아하는 계절은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '봄');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '여름');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '가을');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '겨울');

    insert into question_presets(question_text)
        values ('{name}이(가) 카페에서 제일 자주 시키는 음료는?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '아이스 아메리카노');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '라떼');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '스무디');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '차 종류');

    insert into question_presets(question_text)
        values ('{name}이(가) 갖고 싶은 초능력은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '순간이동');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '시간 멈추기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '마음 읽기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '하늘 날기');

    insert into question_presets(question_text)
        values ('{name}이(가) 복권에 당첨되면 제일 먼저 할 일은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '집 사기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '여행 가기');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '부모님 선물');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '일단 저축');

    insert into question_presets(question_text)
        values ('{name}이(가) 키우고 싶은 동물은?') returning id into inserted_question_id;
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '강아지');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '고양이');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '햄스터');
    insert into question_preset_choices(question_preset_id, choice_text)
        values (inserted_question_id, '앵무새');

end $$;

insert into reward_presets(title, description) values
    ('리액션 한가득', '방장이 1등에게 신나게 리액션해 줘요.'),
    ('칭찬 세 마디', '방장이 1등에게 칭찬 세 마디를 들려줘요.'),
    ('메뉴 결정권', '방장과 밥 먹을 때, 1등이 메뉴를 골라요.');

import unittest,datetime
from unittest.mock import patch
from build_speed_catalog875 import parse_limit,direction,limits

class SpeedCatalogTests(unittest.TestCase):
    def test_strict_units_and_ambiguous_values(self):
        for raw,n in [('80',80),('90 km/h',90),('70 kph',70),('50 kmh',50)]:self.assertEqual(n,parse_limit(raw))
        for raw in ('80;90','50 mph','signals','none','150','SG:urban','50 @ wet','30.5',''):
            self.assertIsNone(parse_limit(raw),raw)
    def test_expressway_class_never_generates_a_default(self):
        self.assertEqual((None,None),limits({'highway':'motorway'},'Pan Island Expressway')[:2])
    def test_directional_limits_and_reverse_oneway(self):
        r=limits({'highway':'primary','maxspeed:forward':'80','maxspeed:backward':'70'},'Test')
        self.assertEqual((80,70),r[:2])
        self.assertEqual((None,80),limits({'highway':'motorway','oneway':'-1','maxspeed':'80'},'Test')[:2])
    def test_conditional_and_variable_records_are_not_guessed(self):
        for tag in ('maxspeed:conditional','maxspeed:variable'):
            self.assertEqual((None,None),limits({'highway':'primary','maxspeed':'80',tag:'40 @ school'},'Test')[:2])
    def test_general_rule_requires_explicit_applicable_map_context(self):
        r=limits({'highway':'residential','maxspeed:type':'SG:urban'},'Test')
        self.assertEqual((50,50,'GENERAL_RULE','GENERAL_RULE'),r[:4])
        self.assertEqual((None,None),limits({'highway':'motorway','maxspeed:type':'SG:urban'},'Test')[:2])
    def test_whole_road_rule_is_not_applied_to_ramps_or_conflicts(self):
        with patch('build_speed_catalog875.RULE_CHECKED',datetime.date.today().isoformat()):
            self.assertEqual('OFFICIAL',limits({'highway':'motorway','maxspeed':'90'},'Kranji Expressway')[2])
        self.assertEqual('MAP_DATA',limits({'highway':'motorway_link','maxspeed':'50'},'Kranji Expressway')[2])
        self.assertEqual('MAP_DATA',limits({'highway':'motorway','maxspeed':'80'},'Kranji Expressway')[2])
    def test_empty_maxspeed_cannot_become_an_official_number(self):
        self.assertEqual((None,None),limits({'highway':'primary'},'Braddell Road')[:2])
    def test_old_statutory_review_does_not_remain_official_indefinitely(self):
        with patch('build_speed_catalog875.RULE_CHECKED','2020-01-01'):
            self.assertEqual('MAP_DATA',limits({'highway':'motorway','maxspeed':'90'},'Kranji Expressway')[2])

if __name__=='__main__':unittest.main()

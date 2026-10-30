package io.jburrow.server.core.routing.domain;

import java.util.List;

/**
 * The word lists random names are drawn from: 256 adjectives and 256 animals, 3 to 7 lower-case letters each, sorted.
 * Chosen to be plain and unambiguous: no brand homographs, no primates (a joined adjective could make a slur),
 * nothing offensive in a common language. {@code WordsTest} checks the rules a reviewer can't.
 */
final class Words {

    static final List<String> ADJECTIVES = words("""
            able active agile airy alert amber ample apt arctic ardent astute azure balmy beige blithe blue bold
            bonny brave breezy brief bright brisk broad bubbly busy calm candid canny caring casual cheery chief
            chill civil clean clear clever cloudy cobalt comfy cool cosmic cosy crisp cubic curly cute daring
            dashing decent deep deft dewy direct distant docile dreamy dry dusky dusty eager early earthy easy
            elated elegant elfin emerald epic even exact expert fair famous fancy fast fertile festive fine firm
            fit fleet floral fluent fluffy flying fond free fresh frisky frosty frugal funny fuzzy gentle giant
            giddy gifted glad gleeful glossy golden good grand grassy great green grey gusty handy happy hardy
            hasty hazy hearty helpful heroic honest hopeful humble icy ideal indigo ivory jade jolly jovial
            joyful keen kind lavish lean level light lilac limber lively loyal lucid lucky lunar lush magic
            major mellow merry mighty mild minty misty modern modest mossy neat nimble noble novel oaken ocean
            olive open optimal orchid pastel patient peachy pearly peppy perky placid plain plucky plush polar
            polite proud quick quiet quirky radiant rapid rare ready regal rich robust rosy round royal rugged
            rustic safe sage salty sandy satin scenic sharp shiny silent silky silver simple sleek slow smart
            smooth snowy snug soft solar solid sonic spicy spry stable starry steady stellar still stoic stout
            sturdy sunny super superb swift tall tame tender tidy tiny topaz tough true trusty unique upbeat
            urban useful vast velvet violet vital vivid warm wavy wild windy wise witty woolly young zany zesty
            """);

    static final List<String> ANIMALS = words("""
            adder agouti alpaca anole ant auk avocet axolotl badger barbel bass bat bear beaver beluga bison
            bittern boar bobcat bongo bream budgie buffalo bunting camel canary caribou carp cat catfish chamois
            cheetah chicken chough cicada civet clam cod condor coot cougar coyote crab crane cricket crow
            cuckoo curlew dace deer dhole dingo dolphin donkey duck dugong dunlin eagle eel egret eider eland
            elk emu ermine falcon ferret finch firefly fossa gannet gar gazelle gecko genet gerbil gnat gnu goat
            goby goose gopher grebe grouse gull guppy haddock hake halibut hamster hare hawk heron herring hippo
            hoopoe hornet horse hound hyena ibex ibis iguana jackal jay jerboa kakapo kestrel kite kiwi koala
            krill kudu ladybug lamb lark lemming leopard limpet linnet lion lizard llama lobster locust loon
            macaw magpie mallard manatee mantis marlin marmot marten merlin midge mink minnow mole moose moray
            moth mouse mullet muskox mussel narwhal nene newt numbat nyala ocelot octopus okapi orca oriole oryx
            osprey ostrich otter owl oyster panda panther parrot peacock pelican penguin perch petrel pigeon
            pika pike pipit plaice plover pony possum prawn puffin quail quokka quoll rabbit raccoon raven ray
            redwing rhea robin rook sable saiga salmon sardine scallop seal serval shark sheep shrew shrimp
            siskin skate skink skylark sloth smelt snail snake snipe sole sparrow spider squid stag stoat stork
            swallow swan takin tapir tarpon teal tench tern tetra thrush tiger toad toucan trout tuna turkey
            turtle urchin vicuna vireo vole vulture wallaby walrus wapiti warbler wasp weasel whale whelk wolf
            wombat wrasse wren yak zander zebra zebu
            """);

    private Words() {}

    private static List<String> words(String block) {
        return List.of(block.strip().split("\\s+"));
    }
}

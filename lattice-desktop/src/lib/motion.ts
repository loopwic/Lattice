import { useReducedMotion, type Transition, type Variants } from "motion/react";

const SPRING_EASE = [0.22, 1, 0.36, 1] as const;

function createTransition(duration: number, reduce: boolean): Transition {
  if (reduce) {
    return { duration: 0.01 };
  }
  return { duration, ease: SPRING_EASE };
}

export function useMotionPresets() {
  const reduce = useReducedMotion() ?? false;

  const quick = createTransition(0.16, reduce);
  const regular = createTransition(0.24, reduce);
  const slow = createTransition(0.32, reduce);

  const pageFade: Variants = reduce
    ? {
        initial: { opacity: 0 },
        enter: { opacity: 1, transition: quick },
        exit: { opacity: 0, transition: quick },
      }
    : {
        initial: { opacity: 0, y: 6 },
        enter: { opacity: 1, y: 0, transition: regular },
        exit: { opacity: 0, y: -4, transition: quick },
      };

  const sectionReveal: Variants = reduce
    ? {
        initial: { opacity: 0 },
        enter: { opacity: 1, transition: quick },
      }
    : {
        initial: { opacity: 0, y: 10 },
        enter: { opacity: 1, y: 0, transition: regular },
      };

  const listStagger: Variants = {
    initial: {},
    enter: {
      transition: {
        staggerChildren: reduce ? 0 : 0.045,
        delayChildren: reduce ? 0 : 0.02,
      },
    },
  };

  const itemReveal: Variants = reduce
    ? {
        initial: { opacity: 0 },
        enter: { opacity: 1, transition: quick },
      }
    : {
        initial: { opacity: 0, y: 8 },
        enter: { opacity: 1, y: 0, transition: regular },
      };

  const overlayFade: Variants = {
    initial: { opacity: 0 },
    enter: { opacity: 1, transition: quick },
    exit: { opacity: 0, transition: quick },
  };

  const popoverScale: Variants = reduce
    ? {
        initial: { opacity: 0 },
        enter: { opacity: 1, transition: quick },
        exit: { opacity: 0, transition: quick },
      }
    : {
        initial: { opacity: 0, y: 10, scale: 0.985 },
        enter: { opacity: 1, y: 0, scale: 1, transition: slow },
        exit: { opacity: 0, y: 6, scale: 0.99, transition: quick },
      };

  return {
    reduce,
    transitions: {
      quick,
      regular,
      slow,
    },
    variants: {
      pageFade,
      sectionReveal,
      listStagger,
      itemReveal,
      overlayFade,
      popoverScale,
    },
  };
}

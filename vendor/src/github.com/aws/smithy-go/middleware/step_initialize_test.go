package middleware

import (
	"testing"
)

func TestInitializeStep_Get(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)

	got, _ := step.Get("A")
	expectID(t, got, "A")
	got, _ = step.Get("B")
	expectID(t, got, "B")

	if _, ok := step.Get("NOT REAL"); ok {
		t.Errorf("shouldn't get fake middleware from list but you did")
	}

	// empty
	step = NewInitializeStep()
	if _, ok := step.Get("A"); ok {
		t.Errorf("shouldn't get something from nothing but you did")
	}
}

func TestInitializeStep_Add(t *testing.T) {
	step := NewInitializeStep()

	step.Add(mockInitializeMiddleware("A"), After)
	expectIDList(t, []string{"A"}, step.List())

	step.Add(mockInitializeMiddleware("B"), After)
	expectIDList(t, []string{"A", "B"}, step.List())

	step.Add(mockInitializeMiddleware("C"), Before)
	expectIDList(t, []string{"C", "A", "B"}, step.List())
}

func TestInitializeStep_Insert(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)
	step.Add(mockInitializeMiddleware("C"), After)

	// before + at the front
	err := step.Insert(mockInitializeMiddleware("D"), "A", Before)
	noError(t, err)
	expectIDList(t, []string{"D", "A", "B", "C"}, step.List())

	// after + at the end
	err = step.Insert(mockInitializeMiddleware("E"), "C", After)
	noError(t, err)
	expectIDList(t, []string{"D", "A", "B", "C", "E"}, step.List())

	// before + somewhere in the middle
	err = step.Insert(mockInitializeMiddleware("F"), "B", Before)
	noError(t, err)
	expectIDList(t, []string{"D", "A", "F", "B", "C", "E"}, step.List())

	// after + somewhere in the middleware
	err = step.Insert(mockInitializeMiddleware("G"), "F", After)
	noError(t, err)
	expectIDList(t, []string{"D", "A", "F", "G", "B", "C", "E"}, step.List())

	// not found
	err = step.Insert(mockInitializeMiddleware("H"), "FALSE", Before)
	if err == nil {
		t.Error("expect err, got none")
	}

	// empty
	step = NewInitializeStep()
	if err := step.Insert(mockInitializeMiddleware("B"), "A", After); err == nil {
		t.Error("expect err, got none")
	}
}

func TestInitializeStep_Swap(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)
	step.Add(mockInitializeMiddleware("C"), After)

	swapped, err := step.Swap("B", mockInitializeMiddleware("D"))
	noError(t, err)
	expectID(t, swapped, "B")
	expectIDList(t, []string{"A", "D", "C"}, step.List())

	// not found
	if _, err := step.Swap("LIES", mockInitializeMiddleware("G")); err == nil {
		t.Error("expect err, got none")
	}
}

func TestInitializeStep_Remove(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)
	step.Add(mockInitializeMiddleware("C"), After)
	step.Add(mockInitializeMiddleware("D"), After)
	step.Add(mockInitializeMiddleware("E"), After)

	// not found
	if _, err := step.Remove("DECEIT"); err == nil {
		t.Error("expect err, got none")
	}

	// at the front
	removed, err := step.Remove("A")
	noError(t, err)
	expectID(t, removed, "A")
	expectIDList(t, []string{"B", "C", "D", "E"}, step.List())

	// at the end
	removed, err = step.Remove("E")
	noError(t, err)
	expectID(t, removed, "E")
	expectIDList(t, []string{"B", "C", "D"}, step.List())

	// somewhere in the middle
	removed, err = step.Remove("C")
	noError(t, err)
	expectID(t, removed, "C")
	expectIDList(t, []string{"B", "D"}, step.List())

	// it's the only one (have to remove two to get there)
	step.Remove("B")
	removed, err = step.Remove("D")
	noError(t, err)
	expectID(t, removed, "D")
	expectIDList(t, nil, step.List())

	// empty
	if _, err := step.Remove("ILLUSION"); err == nil {
		t.Error("expect err, got none")
	}

	// mock post-handle state
	step.Add(mockInitializeMiddleware("A"), After)
	step.tail.Next = &initializeWrapHandler{}
	if _, err := step.Remove("ILLUSION"); err == nil {
		t.Error("expect err, got none")
	}
}

func TestInitializeStep_Clear(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)
	step.Add(mockInitializeMiddleware("C"), After)

	expectIDList(t, []string{"A", "B", "C"}, step.List())
	step.Clear()
	expectIDList(t, nil, step.List())
}

func TestInitializeStep_List(t *testing.T) {
	step := NewInitializeStep()
	step.Add(mockInitializeMiddleware("A"), After)
	step.Add(mockInitializeMiddleware("B"), After)
	step.Add(mockInitializeMiddleware("C"), After)

	// mock post-handle state
	step.tail.Next = &initializeWrapHandler{}

	expectIDList(t, []string{"A", "B", "C"}, step.List())
}
